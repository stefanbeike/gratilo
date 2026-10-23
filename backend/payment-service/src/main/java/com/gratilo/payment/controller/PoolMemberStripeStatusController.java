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
    @Get("/{poolId}/members")
    public HttpResponse<?> get(UUID poolId, Authentication auth) {
        try {
            // The account that owns a pool can now own several pools. Resolve this
            // status request by the immutable pool ID, never by owner ID.
            var pool=pools.find(poolId).orElse(null);
            if (pool==null) return HttpResponse.notFound();
            if (pool.ownerId()==null || !pool.ownerId().toString().equals(auth.getName())) return HttpResponse.status(HttpStatus.FORBIDDEN);
            return HttpResponse.ok(pool.members().stream().map(m -> stripe.refresh(m.userId())).toList()).header("Cache-Control", "no-store");
        } catch (Exception unavailable) { return HttpResponse.status(HttpStatus.SERVICE_UNAVAILABLE); }
    }
}
