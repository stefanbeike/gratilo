package com.gratilo.payment;

import com.gratilo.payment.config.SandboxConfiguration;
import com.gratilo.payment.controller.StripeWebhookController;
import io.micronaut.http.HttpStatus;
import io.micronaut.json.JsonMapper;
import org.junit.jupiter.api.Test;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class SandboxSecurityTest {
    private static final String WEBHOOK = "whsec_local_unit_test_fixture";

    @Test void rejectsLiveKeysAndRealPayoutConfiguration() {
        assertThrows(IllegalStateException.class, () -> new SandboxConfiguration("sk_live_rejected_fixture", WEBHOOK, true, true, "test"));
        assertThrows(IllegalStateException.class, () -> new SandboxConfiguration("", WEBHOOK, true, true, "test"));
        assertThrows(IllegalStateException.class, () -> new SandboxConfiguration("sk_test_fixture", WEBHOOK, false, true, "test"));
        assertThrows(IllegalStateException.class, () -> new SandboxConfiguration("sk_test_fixture", WEBHOOK, true, false, "test"));
        assertDoesNotThrow(() -> new SandboxConfiguration("sk_test_fixture", WEBHOOK, true, true, "test"));
    }

    @Test void rejectsLiveUnsignedAndExpiredWebhooks() throws Exception {
        var controller = new StripeWebhookController(null, WEBHOOK, true, JsonMapper.createDefault());
        long now = System.currentTimeMillis() / 1000;
        String test = "{\"livemode\":false,\"type\":\"unhandled.test\"}";
        String live = "{\"livemode\":true,\"type\":\"unhandled.test\"}";
        assertEquals(HttpStatus.UNAUTHORIZED, controller.receive(test, null).getStatus());
        assertEquals(HttpStatus.UNAUTHORIZED, controller.receive(test, "t=1,v1=wrong").getStatus());
        assertEquals(HttpStatus.UNAUTHORIZED, controller.receive(test, signature(test, now - 600)).getStatus());
        assertEquals(HttpStatus.UNAUTHORIZED, controller.receive(live, signature(live, now)).getStatus());
        assertEquals(HttpStatus.OK, controller.receive(test, signature(test, now)).getStatus());
    }

    @Test void readsRootEventTypeAndOnlyObjectMetadata() throws Exception {
        var seen = new java.util.concurrent.atomic.AtomicReference<java.util.UUID>();
        var repository = (com.gratilo.payment.repository.TransactionRepository) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class[]{com.gratilo.payment.repository.TransactionRepository.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("findById")) { seen.set((java.util.UUID) args[0]); return java.util.Optional.empty(); }
                    throw new AssertionError("Unexpected repository interaction");
                });
        var controller = new StripeWebhookController(repository, WEBHOOK, true, JsonMapper.createDefault());
        var id = java.util.UUID.randomUUID();
        String payload = "{\"livemode\":false,\"data\":{\"object\":{\"type\":\"nested-value\",\"metadata\":{\"transaction_id\":\"" + id + "\"}}},\"type\":\"payment_intent.succeeded\"}";
        assertEquals(HttpStatus.OK, controller.receive(payload, signature(payload, System.currentTimeMillis()/1000)).getStatus());
        assertEquals(id, seen.get());
        seen.set(null);
        String unrelated = "{\"livemode\":false,\"transaction_id\":\"" + id + "\",\"type\":\"unhandled.test\"}";
        assertEquals(HttpStatus.OK, controller.receive(unrelated, signature(unrelated, System.currentTimeMillis()/1000)).getStatus());
        assertNull(seen.get());
    }

    @Test void unpaidDuplicateAndOutOfOrderEventsCannotInventOrUndoTips() throws Exception {
        var tx = new com.gratilo.payment.domain.TransactionEntity();
        tx.setId(java.util.UUID.randomUUID()); tx.setProvider("STRIPE");
        tx.setProviderReference("cs_test_fixture"); tx.setType(com.gratilo.payment.domain.TransactionType.TIP);
        tx.setStatus(com.gratilo.payment.domain.TransactionStatus.PENDING);
        var updates = new java.util.concurrent.atomic.AtomicInteger();
        var repository = (com.gratilo.payment.repository.TransactionRepository) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class[]{com.gratilo.payment.repository.TransactionRepository.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("findById")) return java.util.Optional.of(tx);
                    if (method.getName().equals("update")) { updates.incrementAndGet(); return tx; }
                    throw new AssertionError(method.getName());
                });
        // Even an attempted test-only override cannot admit live events.
        var controller = new StripeWebhookController(repository, WEBHOOK, false, JsonMapper.createDefault());
        java.util.function.BiConsumer<String, String> send = (event, fields) -> {
            String payload = "{\"livemode\":false,\"type\":\"" + event + "\",\"data\":{\"object\":{\"id\":\"cs_test_fixture\",\"metadata\":{\"transaction_id\":\"" + tx.getId() + "\"}," + fields + "}}}";
            try {
                String signed = signature(payload, System.currentTimeMillis()/1000);
                assertEquals(HttpStatus.OK, controller.receive(payload, signed + ",v1=invalid-rotated-signature").getStatus());
            } catch (Exception e) { throw new AssertionError(e); }
        };
        send.accept("checkout.session.completed", "\"payment_status\":\"unpaid\"");
        assertEquals(0, updates.get());
        send.accept("checkout.session.async_payment_succeeded", "\"payment_status\":\"paid\"");
        assertEquals(com.gratilo.payment.domain.TransactionStatus.BOOKED, tx.getStatus());
        assertNotNull(tx.getBookedAt());
        send.accept("checkout.session.completed", "\"payment_status\":\"paid\"");
        send.accept("payment_intent.payment_failed", "\"status\":\"requires_payment_method\"");
        assertEquals(1, updates.get());
        send.accept("charge.refunded", "\"refunded\":false");
        assertEquals(1, updates.get());
        send.accept("charge.refunded", "\"refunded\":true");
        send.accept("payment_intent.succeeded", "\"status\":\"succeeded\"");
        assertEquals(com.gratilo.payment.domain.TransactionStatus.REFUNDED, tx.getStatus());
        assertEquals(2, updates.get());
    }

    @Test void duplicateSuccessStillPersistsPreviouslyMissingStripeIdentifiers() throws Exception {
        var tx = new com.gratilo.payment.domain.TransactionEntity(); tx.setId(java.util.UUID.randomUUID());
        tx.setProvider("STRIPE"); tx.setType(com.gratilo.payment.domain.TransactionType.TIP); tx.setStatus(com.gratilo.payment.domain.TransactionStatus.BOOKED);
        var updates = new java.util.concurrent.atomic.AtomicInteger();
        var repository=(com.gratilo.payment.repository.TransactionRepository)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{com.gratilo.payment.repository.TransactionRepository.class},(p,m,a)->switch(m.getName()){
            case "findById" -> java.util.Optional.of(tx); case "update" -> {updates.incrementAndGet(); yield tx;} default -> throw new AssertionError(m.getName());
        });
        var controller=new StripeWebhookController(repository,WEBHOOK,true,JsonMapper.createDefault());
        String payload="{\"livemode\":false,\"type\":\"payment_intent.succeeded\",\"data\":{\"object\":{\"id\":\"pi_fixture\",\"latest_charge\":\"ch_fixture\",\"metadata\":{\"transaction_id\":\""+tx.getId()+"\"}}}}";
        controller.receive(payload,signature(payload,System.currentTimeMillis()/1000));
        controller.receive(payload,signature(payload,System.currentTimeMillis()/1000));
        assertEquals("pi_fixture",tx.getStripePaymentIntentId()); assertEquals("ch_fixture",tx.getStripeChargeId()); assertEquals(1,updates.get());
    }

    @Test void blocksLiveKeysWhilePoolFeatureCanBeExplicitlyEnabled() {
        assertThrows(IllegalStateException.class, () -> new com.gratilo.payment.service.StripeConnectService("sk_live_fixture", "http://localhost"));
        assertDoesNotThrow(() -> new com.gratilo.payment.config.PoolDisabledConfiguration(true));
        assertDoesNotThrow(() -> new com.gratilo.payment.config.PoolDisabledConfiguration(false));
        var controller = new com.gratilo.payment.controller.ConnectedAccountController(null, null);
        assertEquals(HttpStatus.NOT_FOUND, controller.demoComplete(null).getStatus());
        var filter = new com.gratilo.payment.config.PaymentsFilter(true);
        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, filter.filter(io.micronaut.http.HttpRequest.POST("/payment-service/transactions", "{}")).getStatus());
        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, filter.filter(io.micronaut.http.HttpRequest.POST("/payment-service/transactions/id/refund", "{}")).getStatus());
    }

    @Test void doesNotAcknowledgeAccountsV2ThinEventsWithThePaymentSigningSecret() throws Exception {
        var controller = new StripeWebhookController(null, WEBHOOK, true, JsonMapper.createDefault());
        String payload = "{\"livemode\":false,\"type\":\"v2.core.account.updated\",\"related_object\":{\"id\":\"acct_fixture\"}}";
        assertEquals(HttpStatus.BAD_REQUEST, controller.receive(payload,
                signature(payload, System.currentTimeMillis()/1000)).getStatus());
    }

    private String signature(String payload, long timestamp) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(WEBHOOK.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "t=" + timestamp + ",v1=" + java.util.HexFormat.of().formatHex(mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8)));
    }
}
