package com.gratilo.payment.service;

import com.gratilo.payment.controller.ConnectedAccountController;
import com.gratilo.payment.domain.ConnectedAccountEntity;
import com.gratilo.payment.repository.ConnectedAccountRepository;
import io.micronaut.http.HttpStatus;
import io.micronaut.json.JsonMapper;
import io.micronaut.security.authentication.Authentication;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class ConnectOnboardingTest {
    @Test void localSandboxOnboardingReturnsToLocalFrontendWithoutPublishingLocalBusinessUrl() throws Exception {
        var form = new AtomicReference<String>();
        var accountBody = new AtomicReference<String>();
        var stripe = new StripeConnectService("sk_test_fixture", "http://localhost:3001") {
            @Override protected String jsonCall(String url, String json) { accountBody.set(json); return "{}"; }
            @Override protected String call(String url, String body) { form.set(body); return "{}"; }
        };
        stripe.createAccount("local@example.test", stripe.paymentUrl("local-user"));
        assertFalse(accountBody.get().contains("business_url"));
        stripe.createOnboardingLink("acct_fixture");
        var decoded = java.net.URLDecoder.decode(form.get(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(decoded.contains("return_url=http://localhost:3001/account?stripe_onboarding=return"));
        assertTrue(decoded.contains("refresh_url=http://localhost:3001/account?stripe_onboarding=refresh"));
    }

    @Test void putsBusinessUrlInDefaultsProfile() throws Exception {
        var captured = new AtomicReference<String>();
        var stripe = new StripeConnectService("sk_test_fixture", "https://test.example.com") {
            @Override protected String jsonCall(String url, String json) { captured.set(json); return "{}"; }
        };
        stripe.createAccount("test@example.com", "https://test.example.com/pay/test");
        var body = JsonMapper.createDefault().readValue(captured.get(), Map.class);
        var defaults = (Map<?, ?>) body.get("defaults");
        assertEquals("https://test.example.com/pay/test", ((Map<?, ?>) defaults.get("profile")).get("business_url"));
        assertEquals("express", body.get("dashboard"));
    }

    @Test void createsStripeHostedOnboardingLinkWithSafeReturnRoutes() throws Exception {
        var target = new AtomicReference<String>(); var form = new AtomicReference<String>();
        var stripe = new StripeConnectService("sk_test_fixture", "https://gratilo.eu/") {
            @Override protected String jsonCall(String url, String json) { return "{}"; }
            @Override protected String call(String url, String body) { target.set(url); form.set(body); return "{\"url\":\"https://connect.stripe.com/setup/test\"}"; }
        };
        assertEquals("https://connect.stripe.com/setup/test", StripeConnectService.field(stripe.createOnboardingLink("acct_fixture"), "url"));
        assertEquals("https://api.stripe.com/v1/account_links", target.get());
        var decoded = java.net.URLDecoder.decode(form.get(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(decoded.contains("account=acct_fixture"));
        assertTrue(decoded.contains("type=account_onboarding"));
        assertTrue(decoded.contains("return_url=https://gratilo.eu/account?stripe_onboarding=return"));
        assertTrue(decoded.contains("refresh_url=https://gratilo.eu/account?stripe_onboarding=refresh"));
    }

    @Test void syncProjectsFreshV2EligibilityAndRevokesStaleLocalFlags() {
        var account = new ConnectedAccountEntity(); account.setStripeAccountId("acct_fixture");
        var body = new AtomicReference<>(StripeAccountFixtures.readyJson());
        var repository = (ConnectedAccountRepository) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class[]{ConnectedAccountRepository.class}, (p, m, args) ->
                    m.getName().equals("findByUserId") ? Optional.of(account) : args[0]);
        var stripe = new StripeConnectService("sk_test_fixture", "http://localhost") {
            @Override public String retrieveAccount(String id) {
                if (body.get() == null) throw new IllegalStateException("offline");
                return body.get();
            }
        };
        var controller = new ConnectedAccountController(repository, stripe);
        var auth = Authentication.build(UUID.randomUUID().toString());
        assertEquals(HttpStatus.OK, controller.sync(auth).getStatus());
        assertEquals("COMPLETE", account.getOnboardingStatus());
        assertTrue(account.isChargesEnabled()); assertTrue(account.isDetailsSubmitted());
        body.set(StripeAccountFixtures.readyJson().replace("active", "restricted"));
        assertEquals(HttpStatus.OK, controller.sync(auth).getStatus());
        assertFalse(account.isChargesEnabled()); assertFalse(account.isPayoutsEnabled());
        assertEquals("IN_PROGRESS", account.getOnboardingStatus());
        body.set(StripeAccountFixtures.readyJson()); controller.sync(auth);
        body.set(null);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, controller.sync(auth).getStatus());
        assertFalse(account.isChargesEnabled()); assertFalse(account.isDetailsSubmitted());
    }

    @Test void reusesPersistedAccountAfterOnboardingSessionFailure() {
        var stored = new AtomicReference<ConnectedAccountEntity>();
        var repository = (ConnectedAccountRepository) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class[]{ConnectedAccountRepository.class}, (p, m, args) -> switch (m.getName()) {
                    case "findByUserId" -> Optional.ofNullable(stored.get());
                    case "save", "update" -> { stored.set((ConnectedAccountEntity) args[0]); yield args[0]; }
                    default -> throw new AssertionError(m.getName());
                });
        var created = new java.util.concurrent.atomic.AtomicInteger();
        var sessions = new java.util.concurrent.atomic.AtomicInteger();
        var stripe = new StripeConnectService("sk_test_fixture", "http://localhost:3000") {
            @Override public String createAccount(String email, String url) { created.incrementAndGet(); return "{\"id\":\"acct_fixture\"}"; }
            @Override public void ensureBusinessWebsite(String id, String url) { }
            @Override public String createOnboardingLink(String id) {
                if (sessions.incrementAndGet() == 1) throw new IllegalStateException("session unavailable");
                return "{\"url\":\"https://connect.stripe.com/setup/test_" + sessions.get() + "\"}";
            }
        };
        var controller = new ConnectedAccountController(repository, stripe);
        var auth = Authentication.build(UUID.randomUUID().toString(), Map.of("email", "test@example.com"));
        assertEquals(HttpStatus.BAD_GATEWAY, controller.start(auth).getStatus());
        assertEquals("acct_fixture", stored.get().getStripeAccountId());
        var second = controller.start(auth);
        var third = controller.start(auth);
        assertEquals(HttpStatus.OK, second.getStatus());
        assertEquals("no-store", second.header("Cache-Control"));
        assertEquals("https://connect.stripe.com/setup/test_2", ((Map<?, ?>) second.body()).get("onboardingUrl"));
        assertEquals("https://connect.stripe.com/setup/test_3", ((Map<?, ?>) third.body()).get("onboardingUrl"));
        assertFalse(((Map<?, ?>) second.body()).containsKey("onboardingClientSecret"));
        assertEquals(1, created.get());
        assertEquals(HttpStatus.NOT_FOUND, controller.demoComplete(auth).getStatus());
    }

    @Test void refusesOnboardingLinksOutsideStripeHostedDomain() {
        var account = new AtomicReference<ConnectedAccountEntity>();
        var repository = (ConnectedAccountRepository) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class[]{ConnectedAccountRepository.class}, (p, m, args) -> switch (m.getName()) {
                    case "findByUserId" -> Optional.ofNullable(account.get());
                    case "save", "update" -> { account.set((ConnectedAccountEntity) args[0]); yield args[0]; }
                    default -> throw new AssertionError(m.getName());
                });
        var stripe = new StripeConnectService("sk_test_fixture", "https://gratilo.eu") {
            @Override public String createAccount(String email, String url) { return "{\"id\":\"acct_fixture\"}"; }
            @Override public void ensureBusinessWebsite(String id, String url) { }
            @Override public String createOnboardingLink(String id) { return "{\"url\":\"https://attacker.example/steal\"}"; }
        };
        var controller = new ConnectedAccountController(repository, stripe);
        var response = controller.start(Authentication.build(UUID.randomUUID().toString()));
        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatus());
        assertFalse(response.body().toString().contains("attacker.example"));
    }
}
