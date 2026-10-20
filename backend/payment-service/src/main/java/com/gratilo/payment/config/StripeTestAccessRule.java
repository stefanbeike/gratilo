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
    private final com.gratilo.payment.service.StripeTestAccess access;
    private final java.util.concurrent.ExecutorService executor;

    public StripeTestAccessRule(com.gratilo.payment.service.StripeTestAccess access,
            @jakarta.inject.Named(io.micronaut.scheduling.TaskExecutors.BLOCKING) java.util.concurrent.ExecutorService executor) {
        this.access = access; this.executor = executor;
    }

    @Override public int getOrder() { return Integer.MIN_VALUE + 100; }

    @Override public Publisher<SecurityRuleResult> check(HttpRequest<?> request, Authentication authentication) {
        // Repository-backed authorization must not block Netty's event loop.
        return Publishers.fromCompletableFuture(java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try { return evaluate(request, authentication); }
            catch (Exception unavailable) { return SecurityRuleResult.REJECTED; }
        }, executor));
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
        // Enrollment remains reachable after ordinary registration/login.
        if (path.equals("/payment-service/test-access"))
            return authentication == null ? SecurityRuleResult.REJECTED : SecurityRuleResult.UNKNOWN;
        // This endpoint only confirms an unguessable Stripe test session, never creates a payment.
        if (path.equals("/payment-service/tips/checkout/confirm") && request.getMethod() == HttpMethod.GET)
            return SecurityRuleResult.UNKNOWN;
        String prefix = "/payment-service/tips/";
        if (path.startsWith(prefix)) {
            String[] parts = path.substring(prefix.length()).split("/", -1);
            if (parts.length == 3 && "pools".equals(parts[0])
                    && Set.of("quote", "eligibility", "checkout").contains(parts[2])) {
                try { return access.enabled(UUID.fromString(parts[1])) ? SecurityRuleResult.UNKNOWN : SecurityRuleResult.REJECTED; }
                catch (IllegalArgumentException invalid) { return SecurityRuleResult.REJECTED; }
            }
            if (parts.length != 2 || !Set.of("quote", "eligibility", "checkout").contains(parts[1])) return SecurityRuleResult.REJECTED;
            try { return access.enabled(UUID.fromString(parts[0])) ? SecurityRuleResult.UNKNOWN : SecurityRuleResult.REJECTED; }
            catch (IllegalArgumentException invalid) { return SecurityRuleResult.REJECTED; }
        }
        if (authentication == null) return SecurityRuleResult.REJECTED;
        return access.enabled(UUID.fromString(authentication.getName())) ? SecurityRuleResult.UNKNOWN : SecurityRuleResult.REJECTED;
    }
}
