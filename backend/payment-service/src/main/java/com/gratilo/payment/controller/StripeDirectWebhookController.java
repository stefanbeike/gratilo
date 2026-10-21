package com.gratilo.payment.controller;

import com.gratilo.payment.service.StripeWebhookSignature;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.*;
import io.micronaut.http.annotation.*;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import jakarta.transaction.Transactional;

/** V1 snapshot events from Connected Accounts; not the v2 account thin-event endpoint. */
@Controller("/payment-service/webhooks/stripe-direct")
@Secured(SecurityRule.IS_ANONYMOUS)
@io.micronaut.scheduling.annotation.ExecuteOn(io.micronaut.scheduling.TaskExecutors.BLOCKING)
public class StripeDirectWebhookController {
    private final String secret;
    private final StripeWebhookController processor;
    public StripeDirectWebhookController(@Value("${stripe.direct-webhook-secret:}") String secret,StripeWebhookController processor){this.secret=secret;this.processor=processor;}
    @Post @Transactional
    public HttpResponse<?> receive(@Body String payload,@Header("Stripe-Signature") @Nullable String signature){
        if(secret.isBlank()||!StripeWebhookSignature.valid(secret,payload,signature))return HttpResponse.unauthorized();
        return processor.processVerified(payload,true);
    }
}
