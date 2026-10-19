package com.gratilo.payment.service;

import java.util.*;
import io.micronaut.json.JsonMapper;

final class StripeAccountFixtures {
    static String readyJson() {
        return """
            {"id":"acct_fixture","object":"v2.core.account","livemode":false,"closed":false,
             "applied_configurations":["merchant","recipient"],
             "configuration":{
                "merchant":{"capabilities":{"card_payments":{"status":"active"},"stripe_balance":{"payouts":{"status":"active"}}}},
                "recipient":{"capabilities":{"stripe_balance":{"stripe_transfers":{"status":"active"},"payouts":{"status":"active"}}}}},
             "requirements":{"entries":[],"summary":{}}}
            """;
    }

    static List<String> ineligible() {
        var result = new ArrayList<String>();
        for (String field : List.of("id", "object", "livemode", "configuration", "applied_configurations", "requirements"))
            result.add(changed(field, null, true));
        result.add(changed("id", "acct_other", false));
        result.add(changed("livemode", true, false));
        result.add(changed("closed", true, false));
        result.add(changed("closed", "invalid", false));
        result.add(changed("requirements", "invalid-shape", false));
        result.add(changed("requirements.entries", "invalid-shape", false));
        result.add(changed("requirements.summary", "invalid-shape", false));
        result.add("{\"livemode\":false,\"charges_enabled\":true,\"payouts_enabled\":true,\"capabilities\":{\"transfers\":\"active\"}}");
        for (String path : List.of("configuration.merchant.capabilities.card_payments",
                "configuration.recipient.capabilities.stripe_balance.payouts",
                "configuration.recipient.capabilities.stripe_balance.stripe_transfers")) {
            result.add(changed(path, null, true));
            for (String status : List.of("pending", "restricted", "rejected", "unsupported", "unknown"))
                result.add(changed(path + ".status", status, false));
        }
        for (String deadline : List.of("currently_due", "past_due", "unknown"))
            result.add(changed("requirements.summary.minimum_deadline", Map.of("status", deadline), false));
        result.add(changed("requirements.entries", List.of(Map.of("awaiting_action_from", "stripe")), false));
        result.add(changed("requirements.entries", List.of(Map.of("awaiting_action_from", "user")), false));
        result.add(changed("requirements.entries", List.of("unknown"), false));
        return result;
    }

    @SuppressWarnings("unchecked")
    static String changed(String path, Object value, boolean remove) {
        try {
            var mapper = JsonMapper.createDefault();
            Map<String, Object> root = mapper.readValue(readyJson(), Map.class);
            Map<String, Object> current = root;
            String[] fields = path.split("\\.");
            for (int i = 0; i < fields.length - 1; i++) current = (Map<String, Object>) current.get(fields[i]);
            if (remove) current.remove(fields[fields.length - 1]);
            else current.put(fields[fields.length - 1], value);
            return mapper.writeValueAsString(root);
        } catch (Exception e) { throw new AssertionError(e); }
    }
}
