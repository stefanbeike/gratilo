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
    private boolean ready = true, available = true, failOnce;
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
            var account = new ConnectedAccountEntity(); account.setStripeAccountId("acct_fixture");
            return Optional.of(account);
        });
        var connect = new StripeConnectService("sk_test_fixture", "http://localhost:3000") {
            @Override public String retrieveAccount(String id) {
                return "{\"livemode\":false,\"charges_enabled\":" + ready + ",\"payouts_enabled\":true,\"capabilities\":{\"transfers\":\"active\"}}";
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
