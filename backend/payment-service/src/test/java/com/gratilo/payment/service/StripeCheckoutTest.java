package com.gratilo.payment.service;

import com.gratilo.payment.api.TipPaymentRequest;
import com.gratilo.payment.domain.*;
import com.gratilo.payment.repository.*;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StripeCheckoutTest {
    private final UUID recipient = UUID.randomUUID();
    private TransactionEntity stored;
    private String form, stripeKey;
    private int creates;
    private boolean ready = true, available = true, failOnce, providerFailure;
    private boolean accountPresent = true;
    private String accountId = "acct_fixture";
    private String stripeResponse;
    private int reads;
    private final String session = "cs_test_fixture";

    @SuppressWarnings("unchecked")
    private <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class[]{type}, handler);
    }
    private StripeCheckoutService service() {
        var repository = proxy(TransactionRepository.class, (p, m, a) -> switch (m.getName()) {
            case "findByAccountUserIdAndIdempotencyKey" -> Optional.ofNullable(stored);
            case "findById" -> Optional.ofNullable(stored);
            case "save" -> { stored = (TransactionEntity) a[0]; stored.setId(UUID.randomUUID()); yield stored; }
            case "update" -> a[0];
            default -> throw new AssertionError(m.getName());
        });
        var accounts = proxy(ConnectedAccountRepository.class, (p, m, a) -> {
            if (m.getName().equals("update")) return a[0];
            if (!accountPresent) return Optional.empty();
            var account = new ConnectedAccountEntity(); account.setStripeAccountId(accountId);
            account.setChargesEnabled(true); account.setPayoutsEnabled(true); account.setOnboardingStatus("COMPLETE");
            return Optional.of(account);
        });
        var connect = new StripeConnectService("sk_test_fixture", "http://localhost:3000") {
            @Override public String retrieveAccount(String id) {
                reads++;
                if (providerFailure) throw new IllegalStateException("Stripe unavailable");
                if (stripeResponse != null) return stripeResponse;
                return ready ? StripeAccountFixtures.readyJson() : StripeAccountFixtures.readyJson().replace("active", "pending");
            }
        };
        var recipients = new RecipientAvailability("http://localhost") {
            @Override public void requireEnabled(UUID user) { if (!available) throw new IllegalStateException("paused"); }
        };
        return new StripeCheckoutService(repository, accounts, connect, "sk_test_fixture", "http://localhost/pay/success", "http://localhost/pay/cancelled", 1.5, 25, recipients) {
            @Override protected String stripeRequest(String path, String body, String key) {
                if (body != null) {
                    creates++; form = URLDecoder.decode(body, StandardCharsets.UTF_8); stripeKey = key;
                    if (failOnce) { failOnce = false; throw new IllegalStateException("transport failed"); }
                }
                return "{\"id\":\"" + session + "\",\"url\":\"https://checkout.stripe.com/test\",\"livemode\":false,\"payment_status\":\"paid\",\"metadata\":{\"transaction_id\":\"" + stored.getId() + "\"}}";
            }
        };
    }

    @Test void routesOnlyTipToOneEmployeeAndReusesCheckout() throws Exception {
        var service = service();
        var request = new TipPaymentRequest(500L, "Danke", "caller-key", true);
        var first = service.create(recipient, request).orElseThrow();
        assertTrue(form.contains("unit_amount]=533&"));
        assertTrue(form.contains("transfer_data][amount]=500&"));
        assertTrue(form.contains("transfer_data][destination]=acct_fixture&"));
        assertTrue(form.contains("payment_method_types[0]=card&"));
        assertFalse(form.contains("customer"));
        assertEquals("checkout-" + stored.getId(), stripeKey);
        assertEquals(TransactionStatus.PENDING, stored.getStatus());
        // PostgreSQL JSONB normalizes whitespace and object key order.
        stored.setMetadata("{\"feeCovered\": true, \"totalAmountMinor\": 533}");
        assertEquals(first.transactionId(), service.create(recipient, request).orElseThrow().transactionId());
        assertEquals(1, creates);
        assertThrows(IllegalArgumentException.class, () -> service.create(recipient, new TipPaymentRequest(600L, "Danke", "caller-key", true)));
        assertThrows(IllegalArgumentException.class, () -> service.create(recipient, new TipPaymentRequest(500L, "Danke", "caller-key", false)));
    }

    @Test void retryAfterTransportFailureRetainsStripeIdempotency() throws Exception {
        var service = service(); failOnce = true;
        var request = new TipPaymentRequest(500L, null, "retry");
        assertThrows(IllegalStateException.class, () -> service.create(recipient, request));
        String originalKey = stripeKey;
        service.create(recipient, request);
        assertEquals(originalKey, stripeKey);
        assertEquals(2, creates);
    }

    @Test void rejectsPausedRecipientAndIncompleteStripeOnboarding() {
        var service = service(); ready = false;
        assertThrows(IllegalStateException.class, () -> service.create(recipient, new TipPaymentRequest(500L, null, "key")));
        ready = true; available = false;
        assertThrows(IllegalStateException.class, () -> service.create(recipient, new TipPaymentRequest(500L, null, "key")));
        assertNull(stored); assertEquals(0, creates);
    }

    @Test void missingInvalidAndDemoAccountsCannotCreateSessions() {
        var service = service();
        accountPresent = false;
        assertFalse(service.canReceiveTips(recipient));
        assertThrows(StripeCheckoutService.RecipientNotReadyException.class,
                () -> service.create(recipient, new TipPaymentRequest(500L, null, "missing")));
        accountPresent = true;
        for (String id : new String[]{null, "", "acct_demo_123", "not-an-account"}) {
            accountId = id;
            assertFalse(service.canReceiveTips(recipient));
            assertThrows(StripeCheckoutService.RecipientNotReadyException.class,
                    () -> service.create(recipient, new TipPaymentRequest(500L, null, "invalid")));
        }
        assertEquals(0, reads); assertEquals(0, creates); assertNull(stored);
    }

    @Test void rechecksStripeAfterPublicEligibilityAndRejectsRevokedOrUnavailableAccounts() throws Exception {
        var service = service();
        assertTrue(service.canReceiveTips(recipient));
        assertEquals(1, reads);
        ready = false;
        assertThrows(StripeCheckoutService.RecipientNotReadyException.class,
                () -> service.create(recipient, new TipPaymentRequest(500L, null, "revoked")));
        assertEquals(2, reads); assertEquals(0, creates); assertNull(stored);
        stripeResponse = "invalid JSON";
        assertFalse(service.canReceiveTips(recipient));
        assertThrows(Exception.class, () -> service.create(recipient, new TipPaymentRequest(500L, null, "malformed")));
        stripeResponse = null;
        providerFailure = true;
        assertFalse(service.canReceiveTips(recipient));
        assertThrows(IllegalStateException.class, () -> service.create(recipient, new TipPaymentRequest(500L, null, "offline")));
        assertEquals(0, creates); assertNull(stored);
    }

    @Test void everyV2IneligibleFixtureIsRejectedBeforeCreatingALedgerEntryOrSession() {
        var service = service();
        for (String json : StripeAccountFixtures.ineligible()) {
            stripeResponse = json;
            assertFalse(service.canReceiveTips(recipient));
            assertThrows(IllegalStateException.class, () -> service.create(recipient, new TipPaymentRequest(500L, null, "blocked")));
            assertEquals(0, creates); assertNull(stored);
        }
    }

    @Test void confirmsOnlyMatchingSessionWithoutRevivingRefunds() throws Exception {
        var service = service(); service.create(recipient, new TipPaymentRequest(500L, null, "key"));
        assertFalse(service.confirmSession("cs_test_other"));
        assertTrue(service.confirmSession(session));
        assertNotNull(stored.getBookedAt());
        stored.setStatus(TransactionStatus.REFUNDED);
        assertFalse(service.confirmSession(session));
        assertEquals(TransactionStatus.REFUNDED, stored.getStatus());
        assertFalse(service.confirmSession("cs_live_rejected"));
    }
}
