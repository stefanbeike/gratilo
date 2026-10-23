package com.gratilo.payment.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StripeAccountStatusTest {
    @Test void permitsOnlyTheNativeV2AccountWithAllRequiredActiveCapabilities() throws Exception {
        assertTrue(StripeAccountStatus.parse("acct_fixture", StripeAccountFixtures.readyJson()).canReceiveTips());
        for (String json : StripeAccountFixtures.ineligible())
            assertFalse(StripeAccountStatus.parse("acct_fixture", json).canReceiveTips(), json);
    }

    @Test void futureRequirementsDoNotBlockAnAlreadyEnabledAccount() throws Exception {
        String json = StripeAccountFixtures.changed("requirements.summary.minimum_deadline", java.util.Map.of("status", "eventually_due"), false);
        assertTrue(StripeAccountStatus.parse("acct_fixture", json).canReceiveTips());
        assertTrue(StripeAccountStatus.parse("acct_fixture", StripeAccountFixtures.changed("requirements", null, false)).canReceiveTips());
    }

    @Test void merchantPayoutFlagCannotSubstituteForRecipientPayouts() throws Exception {
        String missing = StripeAccountFixtures.changed("configuration.recipient.capabilities.stripe_balance.payouts", null, true);
        assertFalse(StripeAccountStatus.parse("acct_fixture", missing).canReceiveTips());
    }

    @Test void retrievalRequestsV2FieldsExplicitly() throws Exception {
        var url = new java.util.concurrent.atomic.AtomicReference<String>();
        var stripe = new StripeConnectService("sk_test_fixture", "http://localhost") {
            @Override protected String get(String value) { url.set(value); return StripeAccountFixtures.readyJson(); }
        };
        stripe.retrieveAccount("acct_fixture");
        String request = java.net.URLDecoder.decode(url.get(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(request.startsWith("https://api.stripe.com/v2/core/accounts/acct_fixture?"));
        var includes = java.util.List.of("configuration.merchant", "configuration.recipient", "requirements");
        for (int index = 0; index < includes.size(); index++)
            assertTrue(request.contains("include[" + index + "]=" + includes.get(index)));
        assertFalse(request.contains("include[]"));
    }
    @Test void distinguishesTransferEligibilityFromBankPayoutsAndMerchantCharges() throws Exception {
        String json = StripeAccountFixtures.changed("configuration.recipient.capabilities.stripe_balance.payouts.status", "pending", false);
        var status = StripeAccountStatus.parse("acct_fixture", json);
        assertTrue(status.canReceiveTransfers()); assertFalse(status.canPayout()); assertFalse(status.canReceiveTips());
        assertEquals("READY", status.displayStatus());
    }
    @Test void activeRecipientTransferCapabilityDoesNotRequireMerchantOrPayoutCapability() throws Exception {
        String json = StripeAccountFixtures.readyJson()
                .replace("\"applied_configurations\":[\"merchant\",\"recipient\"]", "\"applied_configurations\":[\"recipient\"]")
                .replace("\"payouts\":{\"status\":\"active\"}", "\"payouts\":{\"status\":\"pending\"}");
        var status = StripeAccountStatus.parse("acct_fixture", json);
        assertTrue(status.canReceiveTransfers());
        assertFalse(status.canPayout());
        assertFalse(status.canReceiveTips());
        assertEquals("READY", status.displayStatus());
    }
    @Test void distinguishesIncompleteReviewAndRestrictedStates() throws Exception {
        var json = StripeAccountFixtures.readyJson().replace("\"stripe_transfers\":{\"status\":\"active\"}","\"stripe_transfers\":{\"status\":\"pending\"}");
        assertEquals("UNDER_REVIEW", StripeAccountStatus.parse("acct_fixture", json).displayStatus());
        json = json.replace("\"entries\":[]","\"entries\":[{\"awaiting_action_from\":\"user\"}]");
        assertEquals("INCOMPLETE", StripeAccountStatus.parse("acct_fixture", json).displayStatus());
        json = StripeAccountFixtures.changed("configuration.recipient.capabilities.stripe_balance.stripe_transfers.status", "restricted", false);
        assertEquals("ACTION_REQUIRED", StripeAccountStatus.parse("acct_fixture", json).displayStatus());
        assertEquals("NOT_STARTED", StripeAccountStatus.notStarted().displayStatus());
        assertEquals("UNAVAILABLE", new StripeAccountStatus(false).displayStatus());
    }
}
