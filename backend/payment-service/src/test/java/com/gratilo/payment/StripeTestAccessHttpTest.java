package com.gratilo.payment;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import org.junit.jupiter.api.Test;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StripeTestAccessHttpTest {
    private static final String USER = "11111111-1111-1111-1111-111111111111";
    private static final String JWT = "local-test-fixture-" + "x".repeat(64);

    @Requires(env = "stripe-access-test")
    @Controller("/payment-service/access-probe")
    @Secured(SecurityRule.IS_ANONYMOUS)
    static class Probe {
        @Get String get() { return "authorized"; }
    }

    @Test void validatesJwtBeforeApplyingAllowlistOnTheHttpRequest() throws Exception {
        var properties = new HashMap<String, Object>();
        properties.put("payments.enabled", true);
        properties.put("payments.test-user-ids", USER);
        properties.put("stripe.secret-key", "sk_test_local_fixture");
        properties.put("stripe.webhook-secret", "whsec_local_fixture");
        properties.put("stripe.sandbox-payouts", true);
        properties.put("internal.api-key", "local-test-only");
        properties.put("micronaut.security.token.jwt.signatures.secret.validation.secret", JWT);
        properties.put("micronaut.server.port", -1);
        properties.put("datasources.default.enabled", false);
        properties.put("jpa.default.enabled", false);
        properties.put("liquibase.enabled", false);
        properties.put("liquibase.datasources.default.enabled", false);
        properties.put("datasources.default.url", "jdbc:postgresql://127.0.0.1:1/unused");
        properties.put("datasources.default.username", "unused");
        properties.put("datasources.default.password", "unused");
        try (EmbeddedServer server = ApplicationContext.run(EmbeddedServer.class, properties, "prd", "stripe-access-test")) {
            var client = HttpClient.newHttpClient();
            var uri = server.getURI().resolve("/payment-service/access-probe");
            for (String token : new String[]{"", "unverified", jwt(UUID.randomUUID().toString(), JWT), jwt(USER, "wrong-signature-fixture-" + "y".repeat(64))}) {
                var builder = HttpRequest.newBuilder(uri);
                if (!token.isEmpty()) builder.header("Authorization", "Bearer " + token);
                int status = client.send(builder.GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode();
                assertTrue(status == 401 || status == 403, "Unauthorized request must be rejected");
            }
            assertEquals(200, client.send(HttpRequest.newBuilder(uri).header("Authorization", "Bearer " + jwt(USER, JWT)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode());
        }
    }

    private static String jwt(String subject, String key) throws Exception {
        var encoder = Base64.getUrlEncoder().withoutPadding();
        String header = encoder.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = encoder.encodeToString(("{\"sub\":\"" + subject + "\",\"exp\":" + (System.currentTimeMillis()/1000 + 120) + "}").getBytes(StandardCharsets.UTF_8));
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String body = header + "." + payload;
        return body + "." + encoder.encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }
}
