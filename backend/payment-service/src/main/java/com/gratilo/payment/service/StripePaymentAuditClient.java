package com.gratilo.payment.service;

import com.gratilo.observability.ObservationEvents;

import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;

/** Deliberately no write operation; the existing application remains test-only. */
@Singleton
public class StripePaymentAuditClient {
    @jakarta.inject.Inject ObservationEvents observationEvents;
    private final String secret;
    public StripePaymentAuditClient(@Value("${stripe.secret-key:}") String secret){StripeConnectService.requireTestKey(secret);this.secret=secret;}
    public boolean configured(){return !secret.isBlank();}
    public boolean livemode(){return false;}
    public Map<String,Object> get(String path,String account)throws Exception {
        if(!path.startsWith("/v1/")||path.contains("://")||secret.isBlank())throw new IllegalStateException("AUDIT_TRANSPORT_UNAVAILABLE");
        var request=HttpRequest.newBuilder(URI.create("https://api.stripe.com"+path)).timeout(Duration.ofSeconds(20)).header("Authorization","Bearer "+secret).header("Stripe-Version","2026-09-30.endive");
        if(account!=null)request.header("Stripe-Account",account);
        var response=ObservationEvents.send(observationEvents, "stripe", request.GET().build());
        if (response.statusCode() != 200) throw new ReadFailure(response.statusCode(),
                response.headers().firstValue("Retry-After").orElse(""));
        return StripePaymentAuditJson.map(response.body());
    }
    static final class ReadFailure extends IllegalStateException {
        final int status;
        final long retryAfterSeconds;

        ReadFailure(int status, String retryAfter) {
            super("AUDIT_STRIPE_READ_FAILED");
            this.status = status;
            this.retryAfterSeconds = retryAfter.matches("[0-9]{1,4}") ? Long.parseLong(retryAfter) : 0;
        }
    }
}
