package com.gratilo.payment;

import com.gratilo.payment.api.*;
import com.gratilo.payment.service.StripeCheckoutService;
import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.server.EmbeddedServer;
import org.junit.jupiter.api.Test;
import java.net.http.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GuestCheckoutHttpTest {
    @Test void anonymousGuestCanUseCheckoutWithoutCustomerRegistrationOutsideTheAwsTestGate() throws Exception {
        var service = new StripeCheckoutService(null, null, null, "sk_test_fixture", "http://localhost/success", "http://localhost/cancel", 1.5, 25, null) {
            @Override public boolean canReceiveTips(UUID user) { return true; }
            @Override public Optional<CheckoutResponse> create(UUID user, TipPaymentRequest request) {
                return Optional.of(new CheckoutResponse("https://checkout.stripe.com/test", UUID.randomUUID().toString(), false));
            }
        };
        var properties = new HashMap<String, Object>();
        properties.put("payments.enabled", true);
        properties.put("stripe.secret-key", "sk_test_fixture");
        properties.put("stripe.webhook-secret", "whsec_fixture");
        properties.put("micronaut.server.port", -1);
        properties.put("datasources.default.enabled", false);
        properties.put("jpa.default.enabled", false);
        properties.put("liquibase.enabled", false);
        properties.put("liquibase.datasources.default.enabled", false);
        try (var context = ApplicationContext.builder().properties(properties).environments("test").build()) {
            context.registerSingleton(StripeCheckoutService.class, service);
            context.start();
            var server = context.getBean(EmbeddedServer.class).start();
            var client = HttpClient.newHttpClient();
            var uri = server.getURI().resolve("/payment-service/tips/" + UUID.randomUUID());
            var response = client.send(HttpRequest.newBuilder(java.net.URI.create(uri + "/checkout"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"tipAmountMinor\":500,\"idempotencyKey\":\"guest-test\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("https://checkout.stripe.com/test"));
            var eligibility = client.send(HttpRequest.newBuilder(java.net.URI.create(uri + "/eligibility")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, eligibility.statusCode());
            assertTrue(eligibility.body().contains("true"));
            assertEquals("no-store", eligibility.headers().firstValue("Cache-Control").orElseThrow());
        }
    }
}
