package com.gratilo.user.controller;

import com.gratilo.user.service.PoolService;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import java.util.UUID;

@Controller("/user-service/pools/public")
@Secured(SecurityRule.IS_ANONYMOUS)
@Requires(property = "features.tip-pools-enabled", value = "true")
public class PublicPoolController {
    private final PoolService pools;
    public PublicPoolController(PoolService pools) { this.pools = pools; }
    @Get("/{ownerId}")
    public HttpResponse<?> get(UUID ownerId) {
        var pool = pools.publicDetails(ownerId);
        return pool == null ? HttpResponse.notFound() : HttpResponse.ok(pool).header("Cache-Control", "no-store");
    }
}
