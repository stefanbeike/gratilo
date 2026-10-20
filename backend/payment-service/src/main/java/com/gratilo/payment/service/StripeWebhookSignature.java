package com.gratilo.payment.service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
public final class StripeWebhookSignature {
    private StripeWebhookSignature() {}
    public static boolean valid(String secret, String payload, String header) {
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
