package com.gratilo.payment.controller;

import com.gratilo.payment.service.*;
import io.micronaut.http.*;
import io.micronaut.http.annotation.*;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.rules.SecurityRule;
import java.util.*;

@Controller("/payment-service/connect-account/pools")
@Secured(SecurityRule.IS_AUTHENTICATED)
@io.micronaut.scheduling.annotation.ExecuteOn(io.micronaut.scheduling.TaskExecutors.BLOCKING)
public class PoolMemberStripeStatusController {
    private final PoolDistributionClient pools;
    private final StripeStatusSync stripe;
    public PoolMemberStripeStatusController(PoolDistributionClient pools, StripeStatusSync stripe) { this.pools=pools; this.stripe=stripe; }
    @Get("/{owner}/members")
    public HttpResponse<?> get(UUID owner, Authentication auth) {
        if (!owner.toString().equals(auth.getName())) return HttpResponse.status(HttpStatus.FORBIDDEN);
        try {
            var pool=pools.find(owner).orElse(null);
            if (pool==null) return HttpResponse.notFound();
            return HttpResponse.ok(pool.members().stream().map(m -> stripe.refresh(m.userId())).toList()).header("Cache-Control", "no-store");
        } catch (Exception unavailable) { return HttpResponse.status(HttpStatus.SERVICE_UNAVAILABLE); }
    }
}
