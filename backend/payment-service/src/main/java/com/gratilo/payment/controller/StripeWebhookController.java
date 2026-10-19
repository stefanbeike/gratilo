package com.gratilo.payment.controller;

import com.gratilo.payment.domain.TransactionStatus;
import com.gratilo.payment.repository.TransactionRepository;
import io.micronaut.context.annotation.Value;
import io.micronaut.http.*;
import io.micronaut.http.annotation.*;
import jakarta.transaction.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

@Controller("/payment-service/webhooks/stripe")
@io.micronaut.scheduling.annotation.ExecuteOn(io.micronaut.scheduling.TaskExecutors.BLOCKING)
public class StripeWebhookController {
    private final TransactionRepository transactions;
    private final String secret;
    private final boolean testOnly;
    private final JsonMapper jsonMapper;
    public StripeWebhookController(TransactionRepository transactions, @Value("${stripe.webhook-secret:}") String secret, @Value("${stripe.test-only:false}") boolean testOnly, JsonMapper jsonMapper) { this.transactions = transactions; this.secret = secret; this.testOnly = true; this.jsonMapper = jsonMapper; }

    @Post
    @Transactional
    public HttpResponse<?> receive(@Body String payload, @Header("Stripe-Signature") @Nullable String signature) {
        if (secret.isBlank() || !validSignature(payload, signature)) return HttpResponse.unauthorized();
        java.util.Map<?, ?> body;
        try {
            body = jsonMapper.readValue(payload, java.util.Map.class);
            if (testOnly && !Boolean.FALSE.equals(body.get("livemode"))) return HttpResponse.unauthorized();
        } catch (java.io.IOException exception) { return HttpResponse.badRequest(); }
        String event = body.get("type") instanceof String value ? value : null;
        var data = body.get("data") instanceof java.util.Map<?, ?> value ? value : java.util.Map.of();
        var object = data.get("object") instanceof java.util.Map<?, ?> value ? value : java.util.Map.of();
        var metadata = object.get("metadata") instanceof java.util.Map<?, ?> value ? value : java.util.Map.of();
        Object transactionId = metadata.get("transaction_id");
        if (transactionId == null) transactionId = metadata.get("transactionId");
        String txId = transactionId instanceof String value ? value : null;
        if (txId != null && event != null) {
            try {
                var tx = transactions.findById(UUID.fromString(txId)).orElse(null);
                if (tx != null) {
                    if (!"STRIPE".equals(tx.getProvider())) return HttpResponse.ok();
                    if (event.startsWith("checkout.session.") && !java.util.Objects.equals(tx.getProviderReference(), object.get("id"))) return HttpResponse.ok();
                    boolean tip = tx.getType() == com.gratilo.payment.domain.TransactionType.TIP;
                    boolean payout = tx.getType() == com.gratilo.payment.domain.TransactionType.PAYOUT;
                    TransactionStatus next = null;
                    if (tip && (event.equals("payment_intent.succeeded")
                            || ((event.equals("checkout.session.completed") || event.equals("checkout.session.async_payment_succeeded"))
                                && "paid".equals(object.get("payment_status"))))) next = TransactionStatus.BOOKED;
                    else if (tip && (event.equals("payment_intent.payment_failed") || event.equals("checkout.session.expired")
                            || event.equals("checkout.session.async_payment_failed"))) next = TransactionStatus.FAILED;
                    else if (tip && event.equals("payment_intent.canceled")) next = TransactionStatus.CANCELLED;
                    else if (tip && event.equals("charge.refunded") && Boolean.TRUE.equals(object.get("refunded"))) next = TransactionStatus.REFUNDED;
                    else if (payout && event.equals("payout.paid")) next = TransactionStatus.BOOKED;
                    else if (payout && (event.equals("payout.failed") || event.equals("payout.canceled"))) next = TransactionStatus.FAILED;
                    // Late failures and duplicate successes cannot overwrite a completed or refunded tip.
                    boolean settled = tx.getStatus() == TransactionStatus.BOOKED || tx.getStatus() == TransactionStatus.COMPLETED;
                    if (next != null && next != tx.getStatus() && tx.getStatus() != TransactionStatus.REFUNDED
                            && (!settled || next == TransactionStatus.REFUNDED || payout)) {
                        tx.setStatus(next);
                        if (next == TransactionStatus.BOOKED) tx.setBookedAt(java.time.OffsetDateTime.now());
                        if (next != TransactionStatus.PENDING) tx.setCompletedAt(java.time.OffsetDateTime.now());
                        transactions.update(tx);
                    }
                }
            } catch (IllegalArgumentException ignored) { }
        }
        return HttpResponse.ok();
    }

    private boolean validSignature(String payload, String header) {
        if (header == null) return false;
        String timestamp = null;
        var signatures = new java.util.ArrayList<String>();
        for (String part : header.split(",")) {
            String[] pair = part.trim().split("=", 2);
            if (pair.length == 2 && pair[0].equals("t")) timestamp = pair[1];
            if (pair.length == 2 && pair[0].equals("v1")) signatures.add(pair[1]);
        }
        if (timestamp == null || signatures.isEmpty()) return false;
        try {
            if (Math.abs(System.currentTimeMillis() / 1000 - Long.parseLong(timestamp)) > 300) return false;
            Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String signed = timestamp + "." + payload;
            StringBuilder hex = new StringBuilder(); for (byte b : mac.doFinal(signed.getBytes(StandardCharsets.UTF_8))) hex.append(String.format("%02x", b));
            return signatures.stream().anyMatch(expected -> MessageDigest.isEqual(hex.toString().getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { return false; }
    }
}
