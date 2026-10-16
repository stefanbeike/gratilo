package com.gratilo.payment;

import com.gratilo.payment.api.TipPaymentRequest;
import com.gratilo.payment.controller.TipController;
import io.micronaut.context.ApplicationContext;
import io.micronaut.http.HttpStatus;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ProductionPaymentTest {
    @Test
    void productionSandboxDisablesDemoCheckoutAndSimulatesPayouts() {
        try (var context = ApplicationContext.builder().environments("prd").environmentPropertySource(false).build()) {
            var env = context.getEnvironment().start();
            assertFalse(env.getRequiredProperty("stripe.demo-enabled", Boolean.class));
            assertTrue(env.getRequiredProperty("stripe.sandbox-payouts", Boolean.class));
            assertTrue(env.getRequiredProperty("stripe.test-only", Boolean.class));
        }
    }

    @Test
    void demoParameterCannotReachPaymentServicesWhenDisabled() {
        var controller = new TipController(null, null, null, 1.5, 25, null, false);
        for (String demo : new String[]{"1", "true", "TRUE"}) {
            assertEquals(HttpStatus.BAD_REQUEST, controller.checkout(UUID.randomUUID(), demo,
                    new TipPaymentRequest(500L, null, "test-only")).getStatus());
        }
    }
}
