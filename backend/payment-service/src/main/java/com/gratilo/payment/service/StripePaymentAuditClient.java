package com.gratilo.payment.service;

import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;

/** Deliberately no write operation; the existing application remains test-only. */
@Singleton
public class StripePaymentAuditClient {
    private final String secret;
    public StripePaymentAuditClient(@Value("${stripe.secret-key:}") String secret){StripeConnectService.requireTestKey(secret);this.secret=secret;}
    public boolean configured(){return !secret.isBlank();}
    public boolean livemode(){return false;}
    public Map<String,Object> get(String path,String account)throws Exception {
        if(!path.startsWith("/v1/")||path.contains("://")||secret.isBlank())throw new IllegalStateException("AUDIT_TRANSPORT_UNAVAILABLE");
        var request=HttpRequest.newBuilder(URI.create("https://api.stripe.com"+path)).timeout(Duration.ofSeconds(20)).header("Authorization","Bearer "+secret).header("Stripe-Version","2026-09-30.endive");
        if(account!=null)request.header("Stripe-Account",account);
        var response=HttpClient.newHttpClient().send(request.GET().build(),HttpResponse.BodyHandlers.ofString());
        if(response.statusCode()!=200)throw new IllegalStateException("AUDIT_STRIPE_READ_FAILED");
        return StripePaymentAuditJson.map(response.body());
    }
}
