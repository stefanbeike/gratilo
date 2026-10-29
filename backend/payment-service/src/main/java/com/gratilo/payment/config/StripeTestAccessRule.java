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
    private final com.gratilo.payment.service.PoolDistributionClient pools;

    public StripeTestAccessRule(com.gratilo.payment.service.StripeTestAccess access,
            @jakarta.inject.Named(io.micronaut.scheduling.TaskExecutors.BLOCKING) java.util.concurrent.ExecutorService executor) {
        this(access, executor, null);
    }

    @jakarta.inject.Inject
    public StripeTestAccessRule(com.gratilo.payment.service.StripeTestAccess access,
            @jakarta.inject.Named(io.micronaut.scheduling.TaskExecutors.BLOCKING) java.util.concurrent.ExecutorService executor,
            com.gratilo.payment.service.PoolDistributionClient pools) {
        this.access = access; this.executor = executor; this.pools = pools;
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
        if ((path.equals("/payment-service/webhooks/stripe") || path.equals("/payment-service/webhooks/stripe-connect") || path.equals("/payment-service/webhooks/stripe-direct"))) return SecurityRuleResult.UNKNOWN;
        if(path.startsWith("/payment-service/admin/stripe-payment-audits"))
            return authentication!=null&&authentication.getRoles().contains("ROLE_ADMIN")?SecurityRuleResult.UNKNOWN:SecurityRuleResult.REJECTED;
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
        // Public configuration only; no payment, account or recipient data.
        if (path.equals("/payment-service/tips/pool-limits") && request.getMethod() == HttpMethod.GET)
            return SecurityRuleResult.UNKNOWN;
        // Public pool pages need a read-only readiness result. The path segment
        // is a pool reference (often the pool UUID), not necessarily the owner's
        // user UUID; checking it against the per-user sandbox enrollment list
        // incorrectly rejected valid public pages with 403 before the controller.
        // Keep quote and checkout behind the recipient test-access gate below.
        if (request.getMethod() == HttpMethod.GET && isPublicPoolEligibilityPath(path))
            return SecurityRuleResult.UNKNOWN;
        String prefix = "/payment-service/tips/";
        if (path.startsWith(prefix)) {
            String[] parts = path.substring(prefix.length()).split("/", -1);
            if (parts.length == 3 && "pools".equals(parts[0])
                    && Set.of("quote", "eligibility", "checkout").contains(parts[2])) {
                try { return poolPaymentAccessEnabled(UUID.fromString(parts[1])) ? SecurityRuleResult.UNKNOWN : SecurityRuleResult.REJECTED; }
                catch (IllegalArgumentException invalid) { return SecurityRuleResult.REJECTED; }
            }
            if (parts.length != 2 || !Set.of("quote", "eligibility", "checkout").contains(parts[1])) return SecurityRuleResult.REJECTED;
            try { return access.enabled(UUID.fromString(parts[0])) ? SecurityRuleResult.UNKNOWN : SecurityRuleResult.REJECTED; }
            catch (IllegalArgumentException invalid) { return SecurityRuleResult.REJECTED; }
        }
        if (authentication == null) return SecurityRuleResult.REJECTED;
        return access.enabled(UUID.fromString(authentication.getName())) ? SecurityRuleResult.UNKNOWN : SecurityRuleResult.REJECTED;
    }

    private static boolean isPublicPoolEligibilityPath(String path) {
        String prefix = "/payment-service/tips/pools/";
        if (!path.startsWith(prefix) || !path.endsWith("/eligibility")) return false;
        String poolReference = path.substring(prefix.length(), path.length() - "/eligibility".length());
        try { UUID.fromString(poolReference); return true; }
        catch (IllegalArgumentException invalid) { return false; }
    }

    private boolean poolPaymentAccessEnabled(UUID poolReference) {
        // The public checkout URL uses the immutable pool ID, while sandbox
        // enrollment is attached to the owning user. Resolve the relationship
        // server-side; never compare a pool ID to a user-ID allowlist.
        if (pools == null) return access.enabled(poolReference); // isolated rule unit tests
        try {
            var pool = pools.find(poolReference).orElse(null);
            return pool != null && pool.ownerId() != null && access.enabled(pool.ownerId());
        } catch (Exception unavailable) {
            return false;
        }
    }
}
