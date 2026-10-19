package com.gratilo.payment.config;

import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.rules.*;
import jakarta.inject.Singleton;
import org.reactivestreams.Publisher;
import java.util.*;

/** JWT authentication is performed by Micronaut before these rules are evaluated. */
@io.micronaut.context.annotation.Context
@Singleton
@Requires(env = "prd")
@Requires(property = "payments.enabled", value = "true")
public class StripeTestAccessRule implements SecurityRule<HttpRequest<?>> {
    private final Set<String> users;

    public StripeTestAccessRule(@Value("${payments.test-user-ids:}") String configuredUsers) {
        var ids = new HashSet<String>();
        for (String value : configuredUsers.split(",")) {
            if (!value.isBlank()) ids.add(UUID.fromString(value.trim()).toString());
        }
        if (ids.isEmpty()) throw new IllegalStateException("PAYMENTS_TEST_USER_IDS is required for authorized Stripe tests");
        users = Set.copyOf(ids);
    }

    @Override public int getOrder() { return Integer.MIN_VALUE + 100; }

    @Override public Publisher<SecurityRuleResult> check(HttpRequest<?> request, Authentication authentication) {
        return Publishers.just(evaluate(request, authentication));
    }

    public SecurityRuleResult evaluate(HttpRequest<?> request, Authentication authentication) {
        String path = request.getPath();
        if (!path.startsWith("/payment-service/")) return SecurityRuleResult.UNKNOWN;
        // Stripe cannot supply a Gratilo JWT. This endpoint always verifies its raw-body signature.
        if (path.equals("/payment-service/webhooks/stripe")) return SecurityRuleResult.UNKNOWN;
        boolean storedRead = request.getMethod() == HttpMethod.GET &&
                (path.equals("/payment-service/connect-account") || path.equals("/payment-service/transactions")
                        || path.startsWith("/payment-service/transactions/"));
        if (storedRead) return SecurityRuleResult.UNKNOWN;
        if (authentication == null || !users.contains(authentication.getName())) return SecurityRuleResult.REJECTED;
        String prefix = "/payment-service/tips/";
        if (path.startsWith(prefix) && !path.equals(prefix + "checkout/confirm")) {
            String recipient = path.substring(prefix.length()).split("/", 2)[0];
            if (!users.contains(recipient)) return SecurityRuleResult.REJECTED;
        }
        return SecurityRuleResult.UNKNOWN;
    }
}
