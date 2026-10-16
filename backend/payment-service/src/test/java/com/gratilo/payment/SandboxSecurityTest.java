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

    private String signature(String payload, long timestamp) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(WEBHOOK.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "t=" + timestamp + ",v1=" + java.util.HexFormat.of().formatHex(mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8)));
    }
}
