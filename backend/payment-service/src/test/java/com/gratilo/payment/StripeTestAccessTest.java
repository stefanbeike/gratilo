package com.gratilo.payment;

import com.gratilo.payment.config.StripeTestAccessRule;
import com.gratilo.payment.service.StripeTestAccess;
import com.gratilo.payment.domain.ConnectedAccountEntity;
import com.gratilo.payment.repository.ConnectedAccountRepository;
import com.gratilo.payment.controller.StripeTestAccessController;
import io.micronaut.context.env.Environment;
import io.micronaut.http.*;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.rules.SecurityRuleResult;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StripeTestAccessTest {
    @Test void onlyPublicPoolReadsBypassTestEnrollment() {
        var rule=new StripeTestAccessRule(access("",code),null);
        var path="/payment-service/tips/pool-limits";
        assertEquals(SecurityRuleResult.UNKNOWN,rule.evaluate(HttpRequest.GET(path),null));
        assertEquals(SecurityRuleResult.UNKNOWN,rule.evaluate(HttpRequest.GET(path),Authentication.build(UUID.randomUUID().toString())));
        for(var request:List.of(HttpRequest.POST(path,"{}"),HttpRequest.PUT(path,"{}"),HttpRequest.GET(path+"/private"),HttpRequest.GET(path+"-admin")))
            assertEquals(SecurityRuleResult.REJECTED,rule.evaluate(request,null));
        var poolId=UUID.randomUUID();
        var publicEligibility="/payment-service/tips/pools/"+poolId+"/eligibility";
        assertEquals(SecurityRuleResult.UNKNOWN,rule.evaluate(HttpRequest.GET(publicEligibility),null));
        assertEquals(SecurityRuleResult.UNKNOWN,rule.evaluate(HttpRequest.GET(publicEligibility),Authentication.build(UUID.randomUUID().toString())));
        for(var request:List.of(HttpRequest.POST(publicEligibility,"{}"),HttpRequest.GET(publicEligibility+"/private"),HttpRequest.GET("/payment-service/tips/pools/not-a-uuid/eligibility")))
            assertEquals(SecurityRuleResult.REJECTED,rule.evaluate(request,null));
        // Public readiness is safe to query, but payment creation remains subject
        // to the existing sandbox-recipient enrollment rule.
        assertEquals(SecurityRuleResult.REJECTED,rule.evaluate(HttpRequest.POST("/payment-service/tips/pools/"+poolId+"/checkout","{}"),null));
    }
    private final UUID tester = UUID.randomUUID(), other = UUID.randomUUID();
    private final String code = "private-test-fixture-" + "a".repeat(32);
    private final Map<UUID, ConnectedAccountEntity> stored = new HashMap<>();
    private final ConnectedAccountRepository repository = (ConnectedAccountRepository) Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class[]{ConnectedAccountRepository.class}, (p, method, args) -> switch(method.getName()) {
                case "findByUserId" -> Optional.ofNullable(stored.get((UUID) args[0]));
                case "save", "update" -> { var account = (ConnectedAccountEntity)args[0]; stored.put(account.getUserId(), account); yield account; }
                default -> throw new AssertionError(method.getName());
            });
    private final Environment env = (Environment) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{Environment.class},
            (p, m, args) -> m.getName().equals("getActiveNames") ? Set.of("prd") : Optional.of(true));
    private StripeTestAccess access(String ids, String activation) { return new StripeTestAccess(() -> repository, env, ids, activation); }

    @Test void emptyConfigurationAndShortCodesFailClosed() {
        assertThrows(IllegalStateException.class, () -> access("", ""));
        assertThrows(IllegalStateException.class, () -> access("", "short"));
        assertThrows(IllegalArgumentException.class, () -> access("invalid", ""));
    }

    @Test void activationUsesOnlyAuthenticatedAccountAndNeverGrantsStripeEligibility() {
        var access = access("", code);
        var controller = new StripeTestAccessController(access);
        var auth = Authentication.build(tester.toString());
        assertEquals(HttpStatus.FORBIDDEN, controller.activate(auth, new StripeTestAccessController.Activation("wrong")).getStatus());
        assertFalse(access.enabled(tester)); assertTrue(stored.isEmpty());
        assertEquals(HttpStatus.OK, controller.activate(auth, new StripeTestAccessController.Activation(code)).getStatus());
        assertTrue(access.enabled(tester)); assertFalse(access.enabled(other));
        var account = stored.get(tester);
        assertNull(account.getStripeAccountId()); assertFalse(account.isChargesEnabled()); assertFalse(account.isPayoutsEnabled());
        assertEquals("NOT_STARTED", account.getOnboardingStatus());
        assertEquals(HttpStatus.OK, controller.revoke(auth).getStatus());
        assertFalse(access.enabled(tester));
    }

    @Test void guestAccessFollowsApprovedRecipientRatherThanCallerIdentity() {
        var access = access("", code);
        var rule = new StripeTestAccessRule(access, null);
        var request = HttpRequest.POST("/payment-service/tips/" + tester + "/checkout", "{}");
        assertEquals(SecurityRuleResult.REJECTED, rule.evaluate(request, null));
        assertEquals(SecurityRuleResult.REJECTED, rule.evaluate(request, Authentication.build(tester.toString())));
        access.activate(tester, code);
        for (String action : new String[]{"checkout", "quote", "eligibility"})
            assertEquals(SecurityRuleResult.UNKNOWN, rule.evaluate(HttpRequest.GET("/payment-service/tips/" + tester + "/" + action), null));
        for (String action : new String[]{"checkout", "quote", "eligibility"})
            assertEquals(SecurityRuleResult.UNKNOWN, rule.evaluate(HttpRequest.GET("/payment-service/tips/pools/" + tester + "/" + action), null));
        assertEquals(SecurityRuleResult.UNKNOWN, rule.evaluate(request, null));
        assertEquals(SecurityRuleResult.REJECTED, rule.evaluate(HttpRequest.POST("/payment-service/tips/pools/" + other + "/checkout", "{}"), null));
        assertEquals(SecurityRuleResult.REJECTED, rule.evaluate(HttpRequest.POST("/payment-service/tips/" + other + "/checkout", "{}"), Authentication.build(tester.toString())));
        access.revoke(tester);
        assertEquals(SecurityRuleResult.REJECTED, rule.evaluate(request, null));
    }

    @Test void poolCheckoutEnrollmentUsesResolvedOwnerRatherThanPoolId() {
        var access=access("",code);access.activate(tester,code);
        UUID poolId=UUID.randomUUID();
        var pools=new com.gratilo.payment.service.PoolDistributionClient("http://localhost","fixture",true){
            @Override public Optional<Distribution> find(UUID ref){return ref.equals(poolId)?Optional.of(new Distribution(poolId,"Pool",List.of(),tester)):Optional.empty();}
        };
        var rule=new StripeTestAccessRule(access,null,pools);
        assertEquals(SecurityRuleResult.UNKNOWN,rule.evaluate(HttpRequest.POST("/payment-service/tips/pools/"+poolId+"/checkout","{}"),null));
        access.revoke(tester);
        assertEquals(SecurityRuleResult.REJECTED,rule.evaluate(HttpRequest.POST("/payment-service/tips/pools/"+poolId+"/checkout","{}"),null));
        // The same pool remains publicly queryable, with only its readiness boolean.
        assertEquals(SecurityRuleResult.UNKNOWN,rule.evaluate(HttpRequest.GET("/payment-service/tips/pools/"+poolId+"/eligibility"),null));
    }

    @Test void enrollmentRequiresLoginAndAccountActionsStillRequireActivatedOwner() {
        var access = access("", code); var rule = new StripeTestAccessRule(access, null);
        assertEquals(SecurityRuleResult.REJECTED, rule.evaluate(HttpRequest.POST("/payment-service/test-access", "{}"), null));
        assertEquals(SecurityRuleResult.UNKNOWN, rule.evaluate(HttpRequest.POST("/payment-service/test-access", "{}"), Authentication.build(tester.toString())));
        for (String path : new String[]{"connect-account/onboarding/start", "connect-account/sync", "payouts"}) {
            var request = HttpRequest.POST("/payment-service/" + path, "{}");
            assertEquals(SecurityRuleResult.REJECTED, rule.evaluate(request, null));
            assertEquals(SecurityRuleResult.REJECTED, rule.evaluate(request, Authentication.build(tester.toString())));
        }
        access.activate(tester, code);
        assertEquals(SecurityRuleResult.UNKNOWN, rule.evaluate(HttpRequest.POST("/payment-service/connect-account/onboarding/start", "{}"), Authentication.build(tester.toString())));
        assertEquals(SecurityRuleResult.REJECTED, rule.evaluate(HttpRequest.POST("/payment-service/connect-account/sync", "{}"), Authentication.build(other.toString())));
    }

    @Test void webhookConfirmationAndPersistedReadsKeepTheirExistingChecks() {
        var rule = new StripeTestAccessRule(access(tester.toString(), ""), null);
        for (String path : new String[]{"/health", "/payment-service/webhooks/stripe", "/payment-service/webhooks/stripe-direct", "/payment-service/webhooks/stripe-connect", "/payment-service/tips/checkout/confirm", "/payment-service/transactions", "/payment-service/connect-account"})
            assertEquals(SecurityRuleResult.UNKNOWN, rule.evaluate(HttpRequest.GET(path), null));
        assertTrue(rule.getOrder() < io.micronaut.security.rules.SecuredAnnotationRule.ORDER);
    }
}
