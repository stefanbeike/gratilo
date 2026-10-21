package com.gratilo.payment.service;

import com.gratilo.payment.domain.ConnectedAccountEntity;
import io.micronaut.json.JsonMapper;
import java.io.IOException;
import java.util.*;

/** Native Accounts v2 eligibility; no fallback to writable profile flags or v1 fields. */
public record StripeAccountStatus(boolean canReceiveTips, boolean canReceiveTransfers, boolean canPayout,
                                  boolean detailsSubmitted, String onboardingStatus, String displayStatus,
                                  boolean chargesEnabled) {
    public StripeAccountStatus(boolean ready) {
        this(ready, ready, ready, ready, ready ? "COMPLETE" : "UNKNOWN", ready ? "READY" : "UNAVAILABLE", ready);
    }
    public static StripeAccountStatus notStarted() {
        return new StripeAccountStatus(false, false, false, false, "NOT_STARTED", "NOT_STARTED", false);
    }
    public static StripeAccountStatus parse(String expectedId, String json) throws IOException {
        Map<?, ?> account = JsonMapper.createDefault().readValue(json, Map.class);
        if (!expectedId.equals(account.get("id")) || !"v2.core.account".equals(account.get("object"))
                || !Boolean.FALSE.equals(account.get("livemode")) || (account.get("closed") != null && !Boolean.FALSE.equals(account.get("closed"))))
            return new StripeAccountStatus(false);
        Object applied = account.get("applied_configurations");
        if (!(applied instanceof List<?> configurations) || !configurations.contains("merchant"))
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
        String transfers = Objects.toString(at(account, "configuration", "recipient", "capabilities", "stripe_balance", "stripe_transfers", "status"), "");
        String payouts = Objects.toString(at(account, "configuration", "recipient", "capabilities", "stripe_balance", "payouts", "status"), "");
        boolean charges = "active".equals(at(account, "configuration", "merchant", "capabilities", "card_payments", "status"));
        String deadline = Objects.toString(at(account, "requirements", "summary", "minimum_deadline", "status"), "");
        Object entries = at(account, "requirements", "entries");
        boolean awaitingStripe = entries instanceof List<?> list && !list.isEmpty()
                && list.stream().allMatch(e -> e instanceof Map<?, ?> m && "stripe".equals(m.get("awaiting_action_from")));
        boolean restricted = List.of("restricted", "rejected", "inactive").contains(transfers)
                || "past_due".equals(deadline);
        boolean transferReady = complete && "active".equals(transfers);
        String display = transferReady ? "READY" : restricted ? "ACTION_REQUIRED"
                : awaitingStripe || complete && "pending".equals(transfers) ? "UNDER_REVIEW"
                : !complete && ("currently_due".equals(deadline) || entries instanceof List<?> list && !list.isEmpty()) ? "INCOMPLETE" : "UNAVAILABLE";
        return new StripeAccountStatus(active && complete, transferReady, "active".equals(payouts), complete || awaitingStripe,
                complete ? "COMPLETE" : awaitingStripe ? "SUBMITTED" : "IN_PROGRESS", display, charges);
    }

    /** Direct charges don't depend on the recipient's separate-transfer or bank-payout capability. */
    public static boolean directReady(String id, String json) throws IOException {
        var status=parse(id,json);
        var account=JsonMapper.createDefault().readValue(json,Map.class);
        return status.detailsSubmitted() && "COMPLETE".equals(status.onboardingStatus()) && status.chargesEnabled()
                && "application".equals(at(account,"defaults","responsibilities","fees_collector"));
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
        account.setChargesEnabled(chargesEnabled);
        account.setPayoutsEnabled(canPayout);
        account.setDetailsSubmitted(detailsSubmitted);
        account.setTransfersEnabled(canReceiveTransfers);
        account.setOnboardingStatus(onboardingStatus);
        account.setStripeStatus(displayStatus);
        account.setStatusCheckedAt(java.time.OffsetDateTime.now());
    }
}
