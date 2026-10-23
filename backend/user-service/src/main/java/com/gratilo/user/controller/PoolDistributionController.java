package com.gratilo.user.controller;

import com.gratilo.user.security.InternalSecret;
import com.gratilo.user.service.PoolService;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.*;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import java.util.UUID;

@Secured(SecurityRule.IS_ANONYMOUS)
@Requires(property = "features.tip-pools-enabled", value = "true")
@Controller("/user-service/internal/pools")
public class PoolDistributionController {
    private final PoolService service;
    private final String expected;
    public PoolDistributionController(PoolService service, @Value("${internal.api-key}") String expected) {
        this.service = service;
        this.expected = expected;
    }
    @Get("/{poolId}")
    public HttpResponse<?> distribution(UUID poolId, @Header("X-Internal-Secret") @Nullable String secret) {
        if (!InternalSecret.matches(expected, secret)) return HttpResponse.unauthorized();
        var result = service.distribution(poolId);
        return result == null ? HttpResponse.notFound() : HttpResponse.ok(result);
    }
}
