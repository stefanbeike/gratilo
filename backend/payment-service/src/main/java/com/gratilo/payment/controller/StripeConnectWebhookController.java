package com.gratilo.payment.controller;

import com.gratilo.payment.service.*;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.*;
import io.micronaut.http.annotation.*;
import io.micronaut.json.JsonMapper;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import java.util.*;

/** Accounts v2 thin notifications have a separate endpoint and signing secret. */
@Controller("/payment-service/webhooks/stripe-connect")
@Secured(SecurityRule.IS_ANONYMOUS)
@io.micronaut.scheduling.annotation.ExecuteOn(io.micronaut.scheduling.TaskExecutors.BLOCKING)
public class StripeConnectWebhookController {
    private final String secret;
    private final StripeConnectService stripe;
    private final StripeStatusSync sync;
    public StripeConnectWebhookController(@Value("${stripe.connect-webhook-secret:}") String secret, StripeConnectService stripe, StripeStatusSync sync) {
        this.secret=secret; this.stripe=stripe; this.sync=sync;
    }
    @Post public HttpResponse<?> receive(@Body String payload, @Header("Stripe-Signature") @Nullable String signature) {
        if (secret.isBlank() || !StripeWebhookSignature.valid(secret, payload, signature)) return HttpResponse.unauthorized();
        try {
            var mapper=JsonMapper.createDefault();
            Map<?,?> notification=mapper.readValue(payload, Map.class);
            String id=Objects.toString(notification.get("id"), "");
            if (!id.matches("evt_[A-Za-z0-9_]+")) return HttpResponse.badRequest();
            Map<?,?> event=mapper.readValue(stripe.retrieveEvent(id), Map.class);
            if (!id.equals(event.get("id")) || !Boolean.FALSE.equals(event.get("livemode"))) return HttpResponse.unauthorized();
            if (!Objects.toString(event.get("type"), "").startsWith("v2.core.account")) return HttpResponse.ok();
            if (!(event.get("related_object") instanceof Map<?,?> related)
                    || !(related.get("id") instanceof String account) || !account.matches("acct_[A-Za-z0-9]+")) return HttpResponse.badRequest();
            if (!sync.refreshAccount(account)) return HttpResponse.status(HttpStatus.SERVICE_UNAVAILABLE); // Fetches current resource; duplicate or reordered events cannot restore stale permissions.
            return HttpResponse.ok();
        } catch (Exception unavailable) { return HttpResponse.status(HttpStatus.SERVICE_UNAVAILABLE); }
    }
}
