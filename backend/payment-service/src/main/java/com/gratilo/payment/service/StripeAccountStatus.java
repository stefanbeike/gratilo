package com.gratilo.payment.service;

import com.gratilo.payment.domain.ConnectedAccountEntity;
import io.micronaut.json.JsonMapper;
import java.io.IOException;
import java.util.*;

/** Native Accounts v2 eligibility; no fallback to writable profile flags or v1 fields. */
public record StripeAccountStatus(boolean canReceiveTips) {
    public static StripeAccountStatus parse(String expectedId, String json) throws IOException {
        Map<?, ?> account = JsonMapper.createDefault().readValue(json, Map.class);
        if (!expectedId.equals(account.get("id")) || !"v2.core.account".equals(account.get("object"))
                || !Boolean.FALSE.equals(account.get("livemode")) || (account.get("closed") != null && !Boolean.FALSE.equals(account.get("closed"))))
            return new StripeAccountStatus(false);
        Object applied = account.get("applied_configurations");
        if (!(applied instanceof List<?> configurations) || !configurations.containsAll(List.of("merchant", "recipient")))
            return new StripeAccountStatus(false);
        boolean active = "active".equals(at(account, "configuration", "merchant", "capabilities", "card_payments", "status"))
                && "active".equals(at(account, "configuration", "recipient", "capabilities", "stripe_balance", "payouts", "status"))
                && "active".equals(at(account, "configuration", "recipient", "capabilities", "stripe_balance", "stripe_transfers", "status"));
        // Requirements must have been explicitly included. A null value means none exist.
        boolean complete = account.containsKey("requirements");
        Object requirements = account.get("requirements");
        if (requirements != null) {
            if (!(requirements instanceof Map<?, ?>)) complete = false;
            else {
                Object deadline = at(account, "requirements", "summary", "minimum_deadline");
                if (deadline != null && !"eventually_due".equals(at(account, "requirements", "summary", "minimum_deadline", "status")))
                    complete = false;
                Object summary = at(account, "requirements", "summary");
                if (summary != null && !(summary instanceof Map<?, ?>)) complete = false;
                Object entries = at(account, "requirements", "entries");
                if (entries != null && !(entries instanceof List<?>)) complete = false;
                if (entries instanceof List<?> list && !list.isEmpty()) {
                    if (!"eventually_due".equals(at(account, "requirements", "summary", "minimum_deadline", "status"))) complete = false;
                    if (list.stream().anyMatch(entry -> !(entry instanceof Map<?, ?> map)
                            || !"user".equals(map.get("awaiting_action_from")))) complete = false;
                }
            }
        }
        return new StripeAccountStatus(active && complete);
    }

    private static Object at(Map<?, ?> root, String... path) {
        Object current = root;
        for (String key : path) {
            if (!(current instanceof Map<?, ?> map)) return null;
            current = map.get(key);
        }
        return current;
    }

    public void applyTo(ConnectedAccountEntity account) {
        // Legacy response field names are projections of the complete v2 decision.
        account.setChargesEnabled(canReceiveTips);
        account.setPayoutsEnabled(canReceiveTips);
        account.setDetailsSubmitted(canReceiveTips);
        account.setOnboardingStatus(canReceiveTips ? "COMPLETE" : "IN_PROGRESS");
    }
}
