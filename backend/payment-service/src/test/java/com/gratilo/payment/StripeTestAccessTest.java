package com.gratilo.payment;

import com.gratilo.payment.config.StripeTestAccessRule;
import io.micronaut.http.HttpRequest;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.rules.SecurityRuleResult;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class StripeTestAccessTest {
    private final String tester = UUID.randomUUID().toString();
    private final String other = UUID.randomUUID().toString();
    private final StripeTestAccessRule rule = new StripeTestAccessRule(tester);

    @Test void emptyAllowlistFailsClosed() {
        assertThrows(IllegalStateException.class, () -> new StripeTestAccessRule(""));
        assertThrows(IllegalArgumentException.class, () -> new StripeTestAccessRule("not-a-user-uuid"));
    }

    @Test void rejectsAnonymousUnlistedAndUnlistedRecipient() {
        var request = HttpRequest.POST("/payment-service/tips/" + tester + "/checkout", "{}");
        assertEquals(SecurityRuleResult.REJECTED, rule.evaluate(request, null));
        assertEquals(SecurityRuleResult.REJECTED, rule.evaluate(request, Authentication.build(other)));
        // A raw header or forwarded identity is never treated as authentication.
        request.bearerAuth("unverified-token").header("X-User-Id", tester);
        assertEquals(SecurityRuleResult.REJECTED, rule.evaluate(request, null));
        assertEquals(SecurityRuleResult.UNKNOWN, rule.evaluate(request, Authentication.build(tester)));
        assertEquals(SecurityRuleResult.REJECTED, rule.evaluate(HttpRequest.POST(
                "/payment-service/tips/" + other + "/checkout", "{}"), Authentication.build(tester)));
    }

    @Test void protectsOnboardingPayoutQuotesAndConfirmation() {
        for (String path : new String[]{"connect-account/onboarding/start", "connect-account/sync", "payouts",
                "tips/checkout/confirm", "tips/" + tester + "/quote"}) {
            var request = HttpRequest.GET("/payment-service/" + path);
            assertEquals(SecurityRuleResult.REJECTED, rule.evaluate(request, null));
            assertEquals(SecurityRuleResult.UNKNOWN, rule.evaluate(request, Authentication.build(tester)));
        }
    }

    @Test void leavesSignatureValidationAndExistingReadAuthorizationToTheirNormalRules() {
        for (String path : new String[]{"/health", "/api/status", "/payment-service/webhooks/stripe",
                "/payment-service/transactions", "/payment-service/transactions/summary", "/payment-service/connect-account"})
            assertEquals(SecurityRuleResult.UNKNOWN, rule.evaluate(HttpRequest.GET(path), null));
        assertTrue(rule.getOrder() < io.micronaut.security.rules.SecuredAnnotationRule.ORDER);
    }
}
