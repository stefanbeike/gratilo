package com.gratilo.payment.service;

import io.micronaut.serde.annotation.Serdeable;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Allowlist of Charge fields. Billing details and raw Stripe objects never enter the reference store. */
public final class StripeChargeReferences {
    private StripeChargeReferences() {}

    @Serdeable
    public record Reference(String source, String value) {}

    record Charge(String id, String intent, String method, String wallet, Instant created,
                  Instant paidAt, Long chargedMinor, String currency, String receipt, List<Reference> references) {}

    static Charge read(Map<?, ?> object, boolean live, Instant confirmedAt) {
        String id = id(object.get("id"), "ch");
        String intent = id(object.get("payment_intent"), "pi");
        Instant created = instant(object.get("created"));
        String currency = match(object.get("currency"), "[a-z]{3}");
        if (!"charge".equals(object.get("object")) || id == null || intent == null || created == null
                || currency == null || !Boolean.valueOf(live).equals(object.get("livemode"))) return null;

        var details = map(object.get("payment_method_details"));
        String method = match(details.get("type"), "[a-z0-9_]{1,80}");
        var fields = map(details.get(Objects.toString(method, "")));
        String wallet = "card".equals(method) ? match(map(fields.get("wallet")).get("type"), "[a-z0-9_]{1,80}") : null;

        List<Reference> references = new ArrayList<>();
        if (method != null) {
            List<String> keys = switch (method) {
                case "paypal", "ideal", "affirm", "alipay", "alma", "amazon_pay", "billie", "bizum",
                     "cashapp", "fpx", "grabpay", "kakao_pay", "kr_card", "naver_pay", "payco",
                     "revolut_pay", "samsung_pay", "satispay", "scalapay", "sequra", "sunbit", "wechat_pay"
                        -> List.of("transaction_id");
                case "afterpay_clearpay" -> List.of("order_id", "reference");
                case "p24", "multibanco", "paynow", "promptpay" -> List.of("reference");
                case "swish", "us_bank_account" -> List.of("payment_reference");
                case "pix" -> List.of("bank_transaction_id");
                case "crypto" -> List.of("transaction_hash");
                case "card" -> List.of("network_transaction_id", "transaction_link_id");
                default -> List.of();
            };
            for (String key : keys) add(references, "payment_method_details." + method + "." + key, fields.get(key));
        }
        add(references, "receipt_number", object.get("receipt_number"));
        boolean paid = "succeeded".equals(object.get("status")) && Boolean.TRUE.equals(object.get("paid"))
                && Boolean.TRUE.equals(object.get("captured"));
        Long captured = paid && object.get("amount_captured") instanceof Number amount && amount.longValue() > 0
                ? amount.longValue() : null;
        return new Charge(id, intent, method, wallet, created, captured == null ? null : confirmedAt,
                captured, currency, captured == null ? null : receipt(object.get("receipt_url")), List.copyOf(references));
    }

    private static void add(List<Reference> values, String source, Object raw) {
        String value = match(raw, "[A-Za-z0-9][A-Za-z0-9 ._:/#-]{0,254}");
        if (value != null) values.add(new Reference("Stripe Charge." + source, value.stripTrailing()));
    }

    private static String receipt(Object raw) {
        if (!(raw instanceof String url) || url.length() > 2048) return null;
        try {
            URI uri = URI.create(url);
            return "https".equals(uri.getScheme()) && "pay.stripe.com".equals(uri.getHost())
                    && uri.getUserInfo() == null && uri.getPort() == -1 && uri.getPath().startsWith("/receipts/")
                    ? url : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    static String id(Object raw, String prefix) {
        return match(raw, prefix + "_[A-Za-z0-9_]{1,245}");
    }

    static Map<?, ?> map(Object raw) {
        return raw instanceof Map<?, ?> object ? object : Map.of();
    }

    static String match(Object raw, String pattern) {
        return raw instanceof String text && text.matches(pattern) ? text : null;
    }

    static Instant instant(Object raw) {
        return raw instanceof Number number && number.longValue() > 0 && number.longValue() <= 253402300799L
                ? Instant.ofEpochSecond(number.longValue()) : null;
    }
}
