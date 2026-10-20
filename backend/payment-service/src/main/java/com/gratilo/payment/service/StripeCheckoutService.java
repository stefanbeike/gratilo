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
import java.math.BigDecimal;
import com.gratilo.payment.domain.PoolTransferEntity;
import com.gratilo.payment.repository.PoolTransferRepository;
import jakarta.inject.Inject;
@Singleton
public class StripeCheckoutService {
    private final RecipientAvailability recipients; private final TransactionRepository transactions; private final ConnectedAccountRepository accounts; private final StripeConnectService connect; private final String secret, successUrl, cancelUrl; private final double feePercent; private final long fixedFeeMinor;
    private final StripeFeeAuditService feeAudit;
    private final PoolDistributionClient poolClient; private final PoolTransferRepository poolTransfers;
    public StripeCheckoutService(TransactionRepository transactions, ConnectedAccountRepository accounts, StripeConnectService connect, @Value("${stripe.secret-key:}") String secret, @Value("${stripe.success-url}") String successUrl, @Value("${stripe.cancel-url}") String cancelUrl, @Value("${stripe.fee-percent:1.5}") double feePercent, @Value("${stripe.fee-fixed-minor:25}") long fixedFeeMinor, RecipientAvailability recipients) {
        this(transactions, accounts, connect, secret, successUrl, cancelUrl, feePercent, fixedFeeMinor, recipients, null, null);
    }
    public StripeCheckoutService(TransactionRepository transactions, ConnectedAccountRepository accounts, StripeConnectService connect, @Value("${stripe.secret-key:}") String secret, @Value("${stripe.success-url}") String successUrl, @Value("${stripe.cancel-url}") String cancelUrl, @Value("${stripe.fee-percent:1.5}") double feePercent, @Value("${stripe.fee-fixed-minor:25}") long fixedFeeMinor, RecipientAvailability recipients, PoolDistributionClient poolClient, PoolTransferRepository poolTransfers) {
        this(transactions, accounts, connect, secret, successUrl, cancelUrl, feePercent, fixedFeeMinor, recipients, poolClient, poolTransfers, null);
    }
    @Inject
    public StripeCheckoutService(TransactionRepository transactions, ConnectedAccountRepository accounts, StripeConnectService connect,
            @Value("${stripe.secret-key:}") String secret, @Value("${stripe.success-url}") String successUrl,
            @Value("${stripe.cancel-url}") String cancelUrl, @Value("${stripe.fee-percent:1.5}") double feePercent,
            @Value("${stripe.fee-fixed-minor:25}") long fixedFeeMinor, RecipientAvailability recipients,
            PoolDistributionClient poolClient, PoolTransferRepository poolTransfers, StripeFeeAuditService feeAudit) {
        this.feeAudit = feeAudit;
        this.recipients=recipients; this.transactions=transactions; this.accounts=accounts; this.connect=connect; this.poolClient=poolClient; this.poolTransfers=poolTransfers; StripeConnectService.requireTestKey(secret); this.secret=secret; this.successUrl=absoluteUrl(successUrl); this.cancelUrl=absoluteUrl(cancelUrl); this.feePercent=feePercent; this.fixedFeeMinor=fixedFeeMinor;
    }
    public boolean isConfigured() { return !secret.isBlank(); }
    public boolean isSandbox() { return true; }

    @Transactional
    public boolean confirmSession(String sessionId) throws Exception {
        return "PAID".equals(checkoutStatus(sessionId).status());
    }

    @Transactional
    public CheckoutStatus checkoutStatus(String sessionId) throws Exception {
        if (secret.isBlank() || sessionId == null || !sessionId.matches("cs_test_[A-Za-z0-9_]+"))
            return new CheckoutStatus("PENDING", false, null, 0, 0, null, null);
        var session = json(stripeRequest("/v1/checkout/sessions/" + enc(sessionId), null, null));
        if (!Boolean.FALSE.equals(session.get("livemode"))) return new CheckoutStatus("PENDING", false, null, 0, 0, null, null);
        var metadata = session.get("metadata") instanceof Map<?, ?> map ? map : Map.of();
        if (!(metadata.get("transaction_id") instanceof String txId)) return new CheckoutStatus("PENDING", false, null, 0, 0, null, null);
        var tx = transactions.findById(UUID.fromString(txId)).orElse(null);
        if (tx == null || !"STRIPE".equals(tx.getProvider()) || !sessionId.equals(tx.getProviderReference()))
            return new CheckoutStatus("PENDING", false, null, 0, 0, null, null);
        boolean paid = "paid".equals(session.get("payment_status"));
        boolean expired = "expired".equals(session.get("status"));
        if (paid && (tx.getStatus() == TransactionStatus.PENDING || tx.getStatus() == TransactionStatus.AUTHORIZED || tx.getStatus() == TransactionStatus.FAILED)) {
            tx.setStatus(TransactionStatus.BOOKED);
            tx.setBookedAt(OffsetDateTime.now()); tx.setCompletedAt(OffsetDateTime.now());
            transactions.update(tx);
        }
        if (expired && (tx.getStatus() == TransactionStatus.PENDING || tx.getStatus() == TransactionStatus.AUTHORIZED)) {
            tx.setStatus(TransactionStatus.FAILED);
            transactions.update(tx);
        }
        boolean definitiveFailure = tx.getStatus() != TransactionStatus.REFUNDED &&
                (expired || ("complete".equals(session.get("status")) && tx.getStatus() == TransactionStatus.FAILED));
        String status = tx.getStatus() == TransactionStatus.BOOKED ? "PAID" : definitiveFailure ? "FAILED" : "PENDING";
        long amountPaid = session.get("amount_total") instanceof Number amount ? amount.longValue() : tx.getAmountMinor();
        String checkoutUrl = "PENDING".equals(status) && "open".equals(session.get("status")) && session.get("url") instanceof String url ? url : null;
        return new CheckoutStatus(status, "PAID".equals(status), tx.getId().toString(), tx.getAmountMinor(), amountPaid,
                tx.getAccountUserId().toString(), checkoutUrl);
    }
    public Optional<CheckoutResponse> create(UUID recipient, TipPaymentRequest request) throws Exception {
        if (request.tipAmountMinor() == null || request.tipAmountMinor() < 100 || request.tipAmountMinor() > 1000000
                || request.idempotencyKey() == null || !request.idempotencyKey().matches("[A-Za-z0-9_-]{1,100}"))
            throw new IllegalArgumentException("Invalid tip amount or idempotency key");
        if (secret.isBlank()) throw new IllegalStateException("Stripe is not configured");
        var account = eligibleAccount(recipient);
        String accountId = account.getStripeAccountId();
        var quote = TipPricing.quote(request.tipAmountMinor(), request.feeCovered(), feePercent, fixedFeeMinor);
        if (request.expectedTotalAmountMinor() != null && request.expectedTotalAmountMinor() != quote.totalAmountMinor()) throw new QuoteChangedException();
        long fee = quote.serviceFeeMinor(), total = quote.totalAmountMinor();
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
            if (feeAudit != null) feeAudit.snapshot(tx, quote);
        }
        // Let Stripe Dashboard dynamic payment-method settings determine Checkout methods.
        String form = param("mode", "payment")
                + param("line_items[0][price_data][currency]", "eur")
                + param("line_items[0][price_data][unit_amount]", Long.toString(total))
                + param("line_items[0][price_data][product_data][name]", "Trinkgeld")
                + param("line_items[0][quantity]", "1")
                + param("metadata[transaction_id]", tx.getId().toString())
                + param("metadata[tip_amount_minor]", Long.toString(request.tipAmountMinor()))
                + param("metadata[fee_covered]", Boolean.toString(request.feeCovered()))
                + param("metadata[fee_surcharge_minor]", Long.toString(fee))
                + param("payment_intent_data[transfer_data][destination]", accountId)
                + param("payment_intent_data[metadata][transaction_id]", tx.getId().toString())
                + param("payment_intent_data[metadata][tip_amount_minor]", Long.toString(request.tipAmountMinor()))
                + param("payment_intent_data[metadata][fee_covered]", Boolean.toString(request.feeCovered()))
                + param("payment_intent_data[metadata][fee_surcharge_minor]", Long.toString(fee))
                + param("payment_intent_data[metadata][amount_charged_minor]", Long.toString(total))
                + param("payment_intent_data[transfer_data][amount]", Long.toString(request.tipAmountMinor()))
                + param("success_url", successUrl) + param("cancel_url", cancelUrl);
        // Scope Stripe's key to our immutable transaction UUID, never the public caller key.
        var session = json(stripeRequest("/v1/checkout/sessions", form, "checkout-" + tx.getId()));
        String id = (String) session.get("id"), url = (String) session.get("url");
        if (!Boolean.FALSE.equals(session.get("livemode")) || id == null || !id.startsWith("cs_test_") || url == null)
            throw new IllegalStateException("Invalid Stripe test Checkout response");
        tx.setProviderReference(id); tx.setPaymentMethod("card"); transactions.update(tx);
        return Optional.of(new CheckoutResponse(url, tx.getId().toString(), false));
    }

    /** A team QR is charged once to Gratilo, then settled as separate transfers after funds are available. */
    @Transactional
    public Optional<CheckoutResponse> createPool(UUID owner, TipPaymentRequest request) throws Exception {
        validateRequest(request);
        if (poolClient == null || poolTransfers == null) throw new PoolNotReadyException();
        if (secret.isBlank()) throw new IllegalStateException("Stripe is not configured");
        var pool = poolClient.find(owner).orElseThrow(PoolNotReadyException::new);
        if (pool.poolId() == null || pool.members().isEmpty()) throw new PoolNotReadyException();
        var eligible = new ArrayList<PoolEligible>();
        for (var member : pool.members()) {
            if (!member.active() || member.sharePercent() <= 0) continue;
            if (!Double.isFinite(member.sharePercent()) || member.sharePercent() > 100) throw new PoolNotReadyException();
            try {
                var account = eligibleAccount(member.userId(), true);
                eligible.add(new PoolEligible(member.userId(), account.getStripeAccountId(), BigDecimal.valueOf(member.sharePercent())));
            } catch (Exception unavailable) {
                throw new PoolNotReadyException();
            }
        }
        if (eligible.isEmpty()) throw new PoolNotReadyException();
        var quote = TipPricing.quote(request.tipAmountMinor(), request.feeCovered(), feePercent, fixedFeeMinor);
        if (request.expectedTotalAmountMinor() != null && request.expectedTotalAmountMinor() != quote.totalAmountMinor()) throw new QuoteChangedException();
        long fee = quote.serviceFeeMinor(), total = quote.totalAmountMinor();
        var weights = eligible.stream().map(e -> new PoolAllocation.Weight(e.userId(), e.sharePercent())).toList();
        var shares = PoolAllocation.split(request.tipAmountMinor(), weights);
        var idempotency = request.idempotencyKey();
        var existing = transactions.findByAccountUserIdAndIdempotencyKey(owner, idempotency);
        TransactionEntity tx;
        if (existing.isPresent()) {
            tx = existing.get();
            var prior = json(tx.getMetadata());
            if (!"STRIPE".equals(tx.getProvider()) || tx.getType() != TransactionType.TIP || tx.getAmountMinor() != request.tipAmountMinor()
                    || !Objects.equals(tx.getDescription(), request.message()) || !pool.poolId().toString().equals(prior.get("poolId"))
                    || !(prior.get("totalAmountMinor") instanceof Number saved) || saved.longValue() != total
                    || !Boolean.valueOf(request.feeCovered()).equals(prior.get("feeCovered")))
                throw new IllegalArgumentException("Idempotency key already used with different payment details");
            if (tx.getStatus() != TransactionStatus.PENDING) throw new IllegalArgumentException("Payment already processed");
            if (tx.getProviderReference() != null) {
                var session = json(stripeRequest("/v1/checkout/sessions/" + enc(tx.getProviderReference()), null, null));
                return Optional.of(new CheckoutResponse((String) session.get("url"), tx.getId().toString(), false));
            }
        } else {
            tx = new TransactionEntity(); tx.setAccountUserId(owner); tx.setAmountMinor(request.tipAmountMinor()); tx.setCurrency("EUR");
            tx.setType(TransactionType.TIP); tx.setStatus(TransactionStatus.PENDING); tx.setDescription(request.message());
            tx.setIdempotencyKey(idempotency); tx.setProvider("STRIPE");
            tx.setMetadata("{\"poolId\":\"" + pool.poolId() + "\",\"poolName\":\"" + jsonEscape(pool.poolName()) + "\",\"totalAmountMinor\":" + total + ",\"feeCovered\":" + request.feeCovered() + ",\"feeSurchargeMinor\":" + fee + "}");
            tx = transactions.save(tx);
            if (feeAudit != null) feeAudit.snapshot(tx, quote);
            var eligibleById = new HashMap<UUID, PoolEligible>(); eligible.forEach(e -> eligibleById.put(e.userId(), e));
            var now = OffsetDateTime.now();
            for (var share : shares) {
                var member = eligibleById.get(share.userId());
                if (member == null || share.amountMinor() <= 0) continue;
                var transfer = new PoolTransferEntity(); transfer.setId(UUID.randomUUID()); transfer.setTransactionId(tx.getId());
                transfer.setRecipientUserId(member.userId()); transfer.setStripeAccountId(member.accountId());
                transfer.setSharePercent(member.sharePercent()); transfer.setAmountMinor(share.amountMinor());
                transfer.setSettlementApproved(true); // New checkout freezes and authorizes its own allocation.
                transfer.setStatus("PENDING"); transfer.setCreatedAt(now); transfer.setUpdatedAt(now); poolTransfers.save(transfer);
            }
        }
        String group = "gratilo_" + tx.getId();
        String metadata = param("transaction_id", tx.getId().toString()) + param("pool_id", pool.poolId().toString());
        String form = param("mode", "payment") + param("line_items[0][price_data][currency]", "eur")
                + param("line_items[0][price_data][unit_amount]", Long.toString(total))
                + param("line_items[0][price_data][product_data][name]", "Trinkgeld für " + pool.poolName())
                + param("line_items[0][quantity]", "1") + param("metadata[transaction_id]", tx.getId().toString())
                + param("metadata[pool_id]", pool.poolId().toString()) + param("metadata[fee_covered]", Boolean.toString(request.feeCovered()))
                + param("metadata[fee_surcharge_minor]", Long.toString(fee)) + param("payment_intent_data[transfer_group]", group)
                + param("payment_intent_data[metadata][transaction_id]", tx.getId().toString())
                + param("payment_intent_data[metadata][pool_id]", pool.poolId().toString())
                + param("success_url", successUrl) + param("cancel_url", cancelUrl);
        var session = json(stripeRequest("/v1/checkout/sessions", form, "checkout-" + tx.getId()));
        String id = (String) session.get("id"), url = (String) session.get("url");
        if (!Boolean.FALSE.equals(session.get("livemode")) || id == null || !id.startsWith("cs_test_") || url == null)
            throw new IllegalStateException("Invalid Stripe test Checkout response");
        tx.setProviderReference(id); tx.setPaymentMethod("card"); transactions.update(tx);
        return Optional.of(new CheckoutResponse(url, tx.getId().toString(), false));
    }

    private void validateRequest(TipPaymentRequest request) {
        if (request == null || request.tipAmountMinor() == null || request.tipAmountMinor() < 100 || request.tipAmountMinor() > 1000000
                || request.idempotencyKey() == null || !request.idempotencyKey().matches("[A-Za-z0-9_-]{1,100}"))
            throw new IllegalArgumentException("Invalid tip amount or idempotency key");
    }

    private record PoolEligible(UUID userId, String accountId, BigDecimal sharePercent) {}
    public static class QuoteChangedException extends IllegalStateException { public QuoteChangedException() { super("Die Gebührenschätzung hat sich geändert. Bitte prüfe den neuen Gesamtbetrag."); } }
    public static class PoolNotReadyException extends IllegalStateException { public PoolNotReadyException() { super("Pool requires all participating members to be transfer-ready"); } }
    private static String jsonEscape(String value) { return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\""); }

    public boolean canReceiveTips(UUID recipient) {
        try { eligibleAccount(recipient); return true; }
        catch (Exception unavailable) { return false; }
    }

    public boolean canReceivePoolTips(UUID owner) {
        if (poolClient == null || poolTransfers == null) return false;
        try {
            var pool = poolClient.find(owner).orElse(null);
            if (pool == null || pool.poolId() == null) return false;
            boolean hasMember = false;
            for (var member : pool.members()) {
                if (member.active() && member.sharePercent() > 0) {
                    if (!Double.isFinite(member.sharePercent()) || member.sharePercent() > 100) return false;
                    eligibleAccount(member.userId(), true);
                    hasMember = true;
                }
            }
            return hasMember;
        } catch (Exception ignored) { }
        return false;
    }

    private ConnectedAccountEntity eligibleAccount(UUID recipient) throws Exception { return eligibleAccount(recipient, false); }
    private ConnectedAccountEntity eligibleAccount(UUID recipient, boolean poolMember) throws Exception {
        if (secret.isBlank()) throw new IllegalStateException("Stripe is not configured");
        try {
            if (poolMember) recipients.requirePoolMemberEnabled(recipient); else recipients.requireEnabled(recipient);
        } catch (Exception unavailable) { throw new RecipientNotReadyException(); }
        var account = accounts.findByUserId(recipient).orElseThrow(() -> new RecipientNotReadyException());
        String id = account.getStripeAccountId();
        if (id == null || !id.matches("acct_[A-Za-z0-9]+") || id.startsWith("acct_demo_"))
            throw new RecipientNotReadyException();
        StripeAccountStatus status;
        try {
            status = StripeAccountStatus.parse(id, connect.retrieveAccount(id));
        } catch (Exception unavailable) {
            new StripeAccountStatus(false).applyTo(account);
            accounts.update(account);
            throw unavailable;
        }
        status.applyTo(account);
        accounts.update(account);
        if (!(poolMember ? status.canReceiveTransfers() : status.canReceiveTips())) throw new RecipientNotReadyException();
        return account;
    }

    public static class RecipientNotReadyException extends IllegalStateException {
        public RecipientNotReadyException() { super("Recipient is not enabled for tips"); }
    }

    protected String stripeRequest(String path, String form, String idempotency) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("https://api.stripe.com" + path))
                .timeout(java.time.Duration.ofSeconds(30)).header("Authorization", "Bearer " + secret);
        if (idempotency != null) builder.header("Idempotency-Key", idempotency);
        var response = HttpClient.newHttpClient().send(form == null ? builder.GET().build()
                : builder.header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300)
            throw StripeApiException.from(response.statusCode(), response.body(), response.headers().firstValue("Request-Id").orElse(""));
        return response.body();
    }
    /** Safe diagnostic for operators; deliberately excludes Stripe's response body and request data. */
    public static final class StripeApiException extends IllegalStateException {
        private final int statusCode;
        private final String errorType, errorCode, parameter, requestId;
        private StripeApiException(int statusCode, String errorType, String errorCode, String parameter, String requestId) {
            super("Stripe API returned HTTP " + statusCode);
            this.statusCode = statusCode;
            this.errorType = safe(errorType);
            this.errorCode = safe(errorCode);
            this.parameter = safe(parameter);
            this.requestId = requestId != null && requestId.matches("req_[A-Za-z0-9]+") ? requestId : "unknown";
        }
        public int statusCode() { return statusCode; }
        public String errorType() { return errorType; }
        public String errorCode() { return errorCode; }
        public String parameter() { return parameter; }
        public String requestId() { return requestId; }
        public static StripeApiException from(int statusCode, String body, String requestId) {
            String type = "unknown", code = "unknown", parameter = "unknown";
            try {
                Object rawError = json(body).get("error");
                if (rawError instanceof Map<?, ?> error) {
                    type = error.get("type") instanceof String value ? value : type;
                    code = error.get("code") instanceof String value ? value : code;
                    parameter = error.get("param") instanceof String value ? value : parameter;
                }
            } catch (Exception ignored) { /* Keep safe unknown placeholders; never expose Stripe's raw body. */ }
            return new StripeApiException(statusCode, type, code, parameter, requestId);
        }
        private static String safe(String value) {
            return value != null && value.matches("[A-Za-z0-9_.\\[\\]-]{1,100}") ? value : "unknown";
        }
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
