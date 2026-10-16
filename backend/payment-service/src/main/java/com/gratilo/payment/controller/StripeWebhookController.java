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
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

@Controller("/payment-service/webhooks/stripe")
public class StripeWebhookController {
    private final TransactionRepository transactions;
    private final String secret;
    public StripeWebhookController(TransactionRepository transactions, @Value("${stripe.webhook-secret:}") String secret) { this.transactions = transactions; this.secret = secret; }

    @Post
    @Transactional
    public HttpResponse<?> receive(@Body String payload, @Header("Stripe-Signature") String signature) {
        if (secret.isBlank() || !validSignature(payload, signature)) return HttpResponse.unauthorized();
        String event = field(payload, "type");
        String txId = field(payload, "transaction_id");
        if (txId == null) txId = field(payload, "transactionId");
        if (txId != null && event != null) {
            try {
                var tx = transactions.findById(UUID.fromString(txId)).orElse(null);
                if (tx != null) {
                    if (event.equals("payment_intent.succeeded") || event.equals("checkout.session.completed")) tx.setStatus(TransactionStatus.BOOKED);
                    else if (event.equals("payment_intent.payment_failed") || event.equals("checkout.session.expired")) tx.setStatus(TransactionStatus.FAILED);
                    else if (event.equals("payout.paid")) tx.setStatus(TransactionStatus.BOOKED);
                    else if (event.equals("payout.failed") || event.equals("payout.canceled")) tx.setStatus(TransactionStatus.FAILED);
                    else if (event.equals("charge.refunded") || event.equals("payment_intent.canceled")) tx.setStatus(TransactionStatus.REFUNDED);
                    transactions.update(tx);
                }
            } catch (IllegalArgumentException ignored) { }
        }
        return HttpResponse.ok();
    }

    private boolean validSignature(String payload, String header) {
        if (header == null) return false;
        String timestamp = null, expected = null;
        for (String part : header.split(",")) { String[] pair = part.split("=", 2); if (pair.length == 2 && pair[0].equals("t")) timestamp = pair[1]; if (pair.length == 2 && pair[0].equals("v1")) expected = pair[1]; }
        if (timestamp == null || expected == null) return false;
        try {
            if (Math.abs(System.currentTimeMillis() / 1000 - Long.parseLong(timestamp)) > 300) return false;
            Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String signed = timestamp + "." + payload;
            StringBuilder hex = new StringBuilder(); for (byte b : mac.doFinal(signed.getBytes(StandardCharsets.UTF_8))) hex.append(String.format("%02x", b));
            return MessageDigest.isEqual(hex.toString().getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) { return false; }
    }
    private static String field(String json, String name) { var m = Pattern.compile("\\\"" + Pattern.quote(name) + "\\\"\\s*:\\s*\\\"([^\\\"]+)").matcher(json == null ? "" : json); return m.find() ? m.group(1) : null; }
}
