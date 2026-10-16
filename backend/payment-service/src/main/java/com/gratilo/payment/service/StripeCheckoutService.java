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
import java.util.regex.Pattern;
@Singleton
public class StripeCheckoutService {
    private final TransactionRepository transactions; private final ConnectedAccountRepository accounts; private final StripeConnectService connect; private final String secret, successUrl, cancelUrl; private final double feePercent; private final long fixedFeeMinor;
    public StripeCheckoutService(TransactionRepository transactions, ConnectedAccountRepository accounts, StripeConnectService connect, @Value("${stripe.secret-key:}") String secret, @Value("${stripe.success-url}") String successUrl, @Value("${stripe.cancel-url}") String cancelUrl, @Value("${stripe.fee-percent:1.5}") double feePercent, @Value("${stripe.fee-fixed-minor:25}") long fixedFeeMinor) { this.transactions=transactions; this.accounts=accounts; this.connect=connect; this.secret=secret; this.successUrl=absoluteUrl(successUrl); this.cancelUrl=absoluteUrl(cancelUrl); this.feePercent=feePercent; this.fixedFeeMinor=fixedFeeMinor; }
    public boolean isConfigured() { return !secret.isBlank(); }
    /** Stripe test keys are used by the local sandbox. Pool payouts are
     * recorded as separate sandbox transactions because a real Checkout
     * Session can only have one destination account. */
    public boolean isSandbox() { return secret.isBlank() || secret.startsWith("sk_test_"); }
    @Transactional
    public boolean confirmSession(String sessionId) throws Exception {
        if (secret.isBlank() || sessionId == null || sessionId.isBlank()) return false;
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("https://api.stripe.com/v1/checkout/sessions/" + URLEncoder.encode(sessionId, StandardCharsets.UTF_8))).header("Authorization", "Bearer " + secret).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300) throw new IllegalStateException("Stripe Checkout-Abfrage fehlgeschlagen: " + response.body());
        String paymentStatus = match(response.body(), "\\\"payment_status\\\"\\s*:\\s*\\\"([^\\\"]+)");
        String txId = match(response.body(), "\\\"transaction_id\\\"\\s*:\\s*\\\"([^\\\"]+)");
        if (!"paid".equals(paymentStatus) || txId == null) return false;
        var tx = transactions.findById(UUID.fromString(txId)).orElse(null);
        if (tx == null) return false;
        if (tx.getStatus() == TransactionStatus.PENDING || tx.getStatus() == TransactionStatus.AUTHORIZED) tx.setStatus(TransactionStatus.BOOKED);
        transactions.update(tx);
        return true;
    }
    @Transactional public Optional<CheckoutResponse> create(UUID recipient, TipPaymentRequest request) throws Exception {
        // The authoritative capability state is Stripe's response. Do not
        // reject a linked account based on a stale local status flag.
        var account = accounts.findByUserId(recipient);
        if (account.isEmpty()) throw new IllegalStateException("Kein Connected Account für diesen Benutzer gefunden.");
        if (account.get().getStripeAccountId() == null || account.get().getStripeAccountId().isBlank()) throw new IllegalStateException("Connected Account besitzt keine Stripe-Account-ID.");
        if (secret.isBlank()) throw new IllegalStateException("STRIPE_SECRET_KEY ist im Payment-Service nicht konfiguriert.");
        connect.ensureBusinessWebsite(account.get().getStripeAccountId(), connect.paymentUrl(recipient.toString()));
        connect.ensureTransferCapability(account.get().getStripeAccountId());
        long fee = request.feeCovered() ? TipPricing.serviceFeeMinor(request.tipAmountMinor(), feePercent, fixedFeeMinor) : 0, total = request.tipAmountMinor() + fee;
        var tx = new TransactionEntity(); tx.setAccountUserId(recipient); tx.setAmountMinor(request.tipAmountMinor()); tx.setCurrency("EUR"); tx.setType(TransactionType.TIP); tx.setStatus(TransactionStatus.PENDING); tx.setDescription(request.message()); tx.setIdempotencyKey(request.idempotencyKey()); tx.setProvider("STRIPE"); tx = transactions.save(tx);
        String form = param("mode", "payment") + param("line_items[0][price_data][currency]", "eur") + param("line_items[0][price_data][unit_amount]", Long.toString(total)) + param("line_items[0][price_data][product_data][name]", "Trinkgeld") + param("line_items[0][quantity]", "1") + param("metadata[transaction_id]", tx.getId().toString())  + param("payment_intent_data[transfer_data][destination]", account.get().getStripeAccountId()) + param("payment_intent_data[metadata][transaction_id]", tx.getId().toString()) + param("payment_intent_data[metadata][tip_amount_minor]", Long.toString(request.tipAmountMinor())) + param("success_url", successUrl) + param("cancel_url", cancelUrl);
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("https://api.stripe.com/v1/checkout/sessions")).header("Authorization", "Bearer " + secret).header("Content-Type", "application/x-www-form-urlencoded").header("Idempotency-Key", request.idempotencyKey() == null ? tx.getId().toString() : request.idempotencyKey()).POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300) {
            tx.setStatus(TransactionStatus.FAILED); tx.setFailureCode("STRIPE_" + response.statusCode()); tx.setFailureMessage(response.body()); transactions.update(tx);
            throw new IllegalStateException("Stripe Checkout API returned " + response.statusCode() + ": " + response.body());
        }
        String id = match(response.body(), "\\\"id\\\"\\s*:\\s*\\\"([^\\\"]+)"); String url = match(response.body(), "\\\"url\\\"\\s*:\\s*\\\"([^\\\"]+)"); tx.setProviderReference(id); tx.setPaymentMethod(match(response.body(), "\\\"payment_method_types\\\"\\s*:\\s*\\[\\s*\\\"([^\\\"]+)")); transactions.update(tx); return Optional.of(new CheckoutResponse(url, tx.getId().toString(), false));
    }
    private static String param(String key,String value) { return enc(key)+"="+enc(value)+"&"; } private static String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); } private static String match(String value,String regex) { var m=Pattern.compile(regex).matcher(value); return m.find()?m.group(1):null; }
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
