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
            @Override public String createOnboardingSession(String id) {
                if (sessions.incrementAndGet() == 1) throw new IllegalStateException("session unavailable");
                return "{\"client_secret\":\"test_session_fixture_" + sessions.get() + "\"}";
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
        assertEquals("test_session_fixture_2", ((com.gratilo.payment.api.ConnectedAccountResponse) second.body()).onboardingClientSecret());
        assertEquals("test_session_fixture_3", ((com.gratilo.payment.api.ConnectedAccountResponse) third.body()).onboardingClientSecret());
        assertEquals(1, created.get());
        assertEquals(HttpStatus.NOT_FOUND, controller.demoComplete(auth).getStatus());
    }
}
