package com.gratilo.payment.config;

import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;

/** Fail before serving requests if any environment is configured for live Stripe. */
@Context
@Requires(property = "payments.enabled", value = "true")
public class SandboxConfiguration {
    public SandboxConfiguration(String key,String webhook,boolean sandboxPayouts,boolean testOnly,String internalKey){
        this(key,webhook,sandboxPayouts,testOnly,internalKey,false,"");
    }
    public SandboxConfiguration(@Value("${stripe.secret-key}") String key,
                                @Value("${stripe.webhook-secret}") String webhook,
                                @Value("${stripe.sandbox-payouts:true}") boolean sandboxPayouts,
                                @Value("${stripe.test-only:true}") boolean testOnly,
                                @Value("${internal.api-key}") String internalKey,
                                @Value("${gratilo.new-pricing-enabled:false}") boolean newPricingEnabled,
                                @Value("${stripe.direct-webhook-secret:}") String directWebhookSecret) {
        this(key,webhook,sandboxPayouts,testOnly,internalKey,newPricingEnabled,directWebhookSecret,false,"");
    }
    @jakarta.inject.Inject
    public SandboxConfiguration(@Value("${stripe.secret-key}") String key,
                                @Value("${stripe.webhook-secret}") String webhook,
                                @Value("${stripe.sandbox-payouts:true}") boolean sandboxPayouts,
                                @Value("${stripe.test-only:true}") boolean testOnly,
                                @Value("${internal.api-key}") String internalKey,
                                @Value("${gratilo.new-pricing-enabled:false}") boolean newPricingEnabled,
                                @Value("${stripe.direct-webhook-secret:}") String directWebhookSecret,
                                @Value("${stripe.manual-payouts-api-approved:false}") boolean manualApproved,
                                @Value("${stripe.manual-payout-approved-accounts:}") String approvedAccounts) {
        if (!key.startsWith("sk_test_") || key.length() <= 8)
            throw new IllegalStateException("STRIPE_SECRET_KEY must be a Stripe test key");
        if (!webhook.startsWith("whsec_") || webhook.length() <= 6)
            throw new IllegalStateException("STRIPE_WEBHOOK_SECRET is required");
        if (!testOnly || !sandboxPayouts && !manualApproved
                || !approvedAccounts.isBlank() && java.util.Arrays.stream(approvedAccounts.split(",", -1)).anyMatch(id -> !id.trim().matches("acct_[A-Za-z0-9]+")))
            throw new IllegalStateException("Stripe test-only mode is required; external test payouts require explicit environment approval; account filters are optional");
        if(!sandboxPayouts&&(!directWebhookSecret.startsWith("whsec_")||directWebhookSecret.length()<=6))throw new IllegalStateException("STRIPE_DIRECT_WEBHOOK_SECRET is required for external test payouts");
        if(newPricingEnabled && (!directWebhookSecret.startsWith("whsec_") || directWebhookSecret.length()<=6))
            throw new IllegalStateException("STRIPE_DIRECT_WEBHOOK_SECRET is required before enabling Direct Charges");
        if (internalKey.isBlank()) throw new IllegalStateException("INTERNAL_API_KEY is required");
    }
}
