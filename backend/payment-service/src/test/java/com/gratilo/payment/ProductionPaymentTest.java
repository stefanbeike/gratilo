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
    void notificationDeliveryCanBeExplicitlyDisabledEvenInSesProfile() {
        try (var context = ApplicationContext.builder().environments("prd")
                .environmentPropertySource(false)
                .properties(java.util.Map.of("TIP_PAYMENT_EMAILS_ENABLED", "false")).build()) {
            assertFalse(context.getEnvironment().start()
                    .getRequiredProperty("notifications.tip-payments-enabled", Boolean.class));
        }
    }

    @Test
    void productionDisablesAllPaymentsByDefault() {
        try (var context = ApplicationContext.builder().environments("prd").environmentPropertySource(false).build()) {
            var env = context.getEnvironment().start();
            assertFalse(env.getRequiredProperty("stripe.demo-enabled", Boolean.class));
            assertTrue(env.getRequiredProperty("stripe.sandbox-payouts", Boolean.class));
            assertFalse(env.getRequiredProperty("payments.enabled", Boolean.class));
            assertTrue(env.getRequiredProperty("stripe.test-only", Boolean.class));
            assertTrue(env.getRequiredProperty("notifications.tip-payments-enabled", Boolean.class));
            assertEquals(new java.math.BigDecimal("3.15"), env.getRequiredProperty("stripe.fee-percent", java.math.BigDecimal.class));
            assertEquals(25L, env.getRequiredProperty("stripe.fee-fixed-minor", Long.class));
        }
    }

    @Test
    void demoParameterCannotReachPaymentServicesWhenDisabled() {
        var controller = new TipController(null, new java.math.BigDecimal("1.5"), 25);
        for (String demo : new String[]{"1", "true", "TRUE"}) {
            assertEquals(HttpStatus.BAD_REQUEST, controller.checkout(UUID.randomUUID(), demo,
                    new TipPaymentRequest(500L, null, "test-only")).getStatus());
        }
    }
}
