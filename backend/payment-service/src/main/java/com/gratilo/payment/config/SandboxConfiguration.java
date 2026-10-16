package com.gratilo.payment.config;

import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;

/** Fail before serving requests if the ECS sandbox is configured for live Stripe. */
@Context
@Requires(env = "prd")
public class SandboxConfiguration {
    public SandboxConfiguration(@Value("${stripe.secret-key}") String key,
                                @Value("${stripe.webhook-secret}") String webhook,
                                @Value("${stripe.sandbox-payouts}") boolean sandboxPayouts,
                                @Value("${stripe.test-only}") boolean testOnly,
                                @Value("${internal.api-key}") String internalKey) {
        if (!key.startsWith("sk_test_") || key.length() <= 8)
            throw new IllegalStateException("STRIPE_SECRET_KEY must be a Stripe test key");
        if (!webhook.startsWith("whsec_") || webhook.length() <= 6)
            throw new IllegalStateException("STRIPE_WEBHOOK_SECRET is required");
        if (!sandboxPayouts || !testOnly)
            throw new IllegalStateException("Stripe test-only mode and simulated payouts must remain enabled");
        if (internalKey.isBlank()) throw new IllegalStateException("INTERNAL_API_KEY is required");
    }
}
