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
        for (String include : java.util.List.of("configuration.merchant", "configuration.recipient", "requirements"))
            assertTrue(request.contains("=" + include));
    }
}
