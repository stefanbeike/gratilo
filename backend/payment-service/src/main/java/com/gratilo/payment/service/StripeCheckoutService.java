package com.gratilo.payment.service;
import com.gratilo.payment.api.*;
import com.gratilo.payment.domain.*;
import com.gratilo.payment.repository.*;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;
import java.net.URI;
import java.net.http.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;
@Singleton
public class StripeCheckoutService {
    private final RecipientAvailability recipients; private final TransactionRepository transactions; private final ConnectedAccountRepository accounts; private final StripeConnectService connect; private final String secret, successUrl, cancelUrl; private final double feePercent; private final long fixedFeeMinor;
    public StripeCheckoutService(TransactionRepository transactions, ConnectedAccountRepository accounts, StripeConnectService connect, @Value("${stripe.secret-key:}") String secret, @Value("${stripe.success-url}") String successUrl, @Value("${stripe.cancel-url}") String cancelUrl, @Value("${stripe.fee-percent:1.5}") double feePercent, @Value("${stripe.fee-fixed-minor:25}") long fixedFeeMinor, RecipientAvailability recipients) { this.recipients=recipients; this.transactions=transactions; this.accounts=accounts; this.connect=connect; StripeConnectService.requireTestKey(secret); this.secret=secret; this.successUrl=absoluteUrl(successUrl); this.cancelUrl=absoluteUrl(cancelUrl); this.feePercent=feePercent; this.fixedFeeMinor=fixedFeeMinor; }
    public boolean isConfigured() { return !secret.isBlank(); }
    public boolean isSandbox() { return true; }

    @Transactional
    public boolean confirmSession(String sessionId) throws Exception {
        if (secret.isBlank() || sessionId == null || !sessionId.matches("cs_test_[A-Za-z0-9_]+")) return false;
        var session = json(stripeRequest("/v1/checkout/sessions/" + enc(sessionId), null, null));
        if (!Boolean.FALSE.equals(session.get("livemode")) || !"paid".equals(session.get("payment_status"))) return false;
        var metadata = session.get("metadata") instanceof Map<?, ?> map ? map : Map.of();
        if (!(metadata.get("transaction_id") instanceof String txId)) return false;
        var tx = transactions.findById(UUID.fromString(txId)).orElse(null);
        if (tx == null || !"STRIPE".equals(tx.getProvider()) || !sessionId.equals(tx.getProviderReference())) return false;
        if (tx.getStatus() == TransactionStatus.PENDING || tx.getStatus() == TransactionStatus.AUTHORIZED || tx.getStatus() == TransactionStatus.FAILED) {
            tx.setStatus(TransactionStatus.BOOKED);
            tx.setBookedAt(OffsetDateTime.now()); tx.setCompletedAt(OffsetDateTime.now());
            transactions.update(tx);
        }
        return tx.getStatus() == TransactionStatus.BOOKED;
    }
    public Optional<CheckoutResponse> create(UUID recipient, TipPaymentRequest request) throws Exception {
        if (request.tipAmountMinor() == null || request.tipAmountMinor() < 100 || request.tipAmountMinor() > 1000000
                || request.idempotencyKey() == null || !request.idempotencyKey().matches("[A-Za-z0-9_-]{1,100}"))
            throw new IllegalArgumentException("Invalid tip amount or idempotency key");
        if (secret.isBlank()) throw new IllegalStateException("Stripe is not configured");
        recipients.requireEnabled(recipient);
        var account = accounts.findByUserId(recipient).orElseThrow(() -> new IllegalStateException("Connected account missing"));
        String accountId = account.getStripeAccountId();
        if (accountId == null || !accountId.matches("acct_[A-Za-z0-9]+") || accountId.startsWith("acct_demo_"))
            throw new IllegalStateException("Connected account unavailable");
        // Re-read Stripe's state so stale local or client-written flags cannot authorize payments.
        var remote = json(connect.retrieveAccount(accountId));
        if (!Boolean.FALSE.equals(remote.get("livemode")) || !Boolean.TRUE.equals(remote.get("charges_enabled"))
                || !Boolean.TRUE.equals(remote.get("payouts_enabled"))) throw new IllegalStateException("Stripe onboarding incomplete");
        var capabilities = remote.get("capabilities") instanceof Map<?, ?> map ? map : Map.of();
        if (!"active".equals(capabilities.get("transfers"))) throw new IllegalStateException("Stripe transfers unavailable");
        long fee = request.feeCovered() ? TipPricing.serviceFeeMinor(request.tipAmountMinor(), feePercent, fixedFeeMinor) : 0, total = request.tipAmountMinor() + fee;
        String key = request.idempotencyKey();
        if (key == null || key.isBlank()) throw new IllegalArgumentException("An idempotency key is required");
        var existing = transactions.findByAccountUserIdAndIdempotencyKey(recipient, key);
        var tx = existing.orElseGet(TransactionEntity::new);
        String pricing = "{\"totalAmountMinor\":" + total + ",\"feeCovered\":" + request.feeCovered() + "}";
        if (existing.isPresent()) {
            var savedPricing = json(tx.getMetadata());
            if (!"STRIPE".equals(tx.getProvider()) || tx.getAmountMinor() != request.tipAmountMinor()
                    || !Objects.equals(tx.getDescription(), request.message()) || !(savedPricing.get("totalAmountMinor") instanceof Number savedTotal) || savedTotal.longValue() != total
                    || !Boolean.valueOf(request.feeCovered()).equals(savedPricing.get("feeCovered")))
                throw new IllegalArgumentException("Idempotency key already used with different payment details");
            if (tx.getStatus() != TransactionStatus.PENDING) throw new IllegalArgumentException("Payment already processed");
            if (tx.getProviderReference() != null) {
                var session = json(stripeRequest("/v1/checkout/sessions/" + enc(tx.getProviderReference()), null, null));
                return Optional.of(new CheckoutResponse((String) session.get("url"), tx.getId().toString(), false));
            }
        } else {
            tx.setAccountUserId(recipient); tx.setAmountMinor(request.tipAmountMinor()); tx.setCurrency("EUR");
            tx.setType(TransactionType.TIP); tx.setStatus(TransactionStatus.PENDING); tx.setDescription(request.message());
            tx.setIdempotencyKey(key); tx.setProvider("STRIPE"); tx.setMetadata(pricing);
            tx = transactions.save(tx);
        }
        String form = param("mode", "payment") + param("payment_method_types[0]", "card") + param("line_items[0][price_data][currency]", "eur") + param("line_items[0][price_data][unit_amount]", Long.toString(total)) + param("line_items[0][price_data][product_data][name]", "Trinkgeld") + param("line_items[0][quantity]", "1") + param("metadata[transaction_id]", tx.getId().toString())  + param("payment_intent_data[transfer_data][destination]", accountId) + param("payment_intent_data[metadata][transaction_id]", tx.getId().toString()) + param("payment_intent_data[metadata][tip_amount_minor]", Long.toString(request.tipAmountMinor())) + param("payment_intent_data[transfer_data][amount]", Long.toString(request.tipAmountMinor())) + param("success_url", successUrl) + param("cancel_url", cancelUrl);
        // Scope Stripe's key to our immutable transaction UUID, never the public caller key.
        var session = json(stripeRequest("/v1/checkout/sessions", form, "checkout-" + tx.getId()));
        String id = (String) session.get("id"), url = (String) session.get("url");
        if (!Boolean.FALSE.equals(session.get("livemode")) || id == null || !id.startsWith("cs_test_") || url == null)
            throw new IllegalStateException("Invalid Stripe test Checkout response");
        tx.setProviderReference(id); tx.setPaymentMethod("card"); transactions.update(tx);
        return Optional.of(new CheckoutResponse(url, tx.getId().toString(), false));
    }

    protected String stripeRequest(String path, String form, String idempotency) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("https://api.stripe.com" + path))
                .timeout(java.time.Duration.ofSeconds(30)).header("Authorization", "Bearer " + secret);
        if (idempotency != null) builder.header("Idempotency-Key", idempotency);
        var response = HttpClient.newHttpClient().send(form == null ? builder.GET().build()
                : builder.header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300) throw new IllegalStateException("Stripe Checkout API returned " + response.statusCode());
        return response.body();
    }
    private static Map<?, ?> json(String value) throws java.io.IOException {
        return io.micronaut.json.JsonMapper.createDefault().readValue(value, Map.class);
    }
    private static String param(String key,String value) { return enc(key)+"="+enc(value)+"&"; } private static String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    static String absoluteUrl(String value) {
        String url = value == null ? "" : value.trim();
        // Stripe replaces this placeholder after checkout; braces are not URI characters.
        URI uri = URI.create(url.replace("{CHECKOUT_SESSION_ID}", "checkout-session"));
        if (uri.getHost() == null || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("Stripe redirect URL must be an absolute HTTP(S) URL");
        }
        return url;
    }
}
