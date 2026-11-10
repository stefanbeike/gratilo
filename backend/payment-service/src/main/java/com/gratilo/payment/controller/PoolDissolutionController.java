package com.gratilo.payment.controller;

import com.gratilo.payment.service.PoolCheckoutGate;
import io.micronaut.context.annotation.*;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.*;
import io.micronaut.http.annotation.*;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.scheduling.annotation.*;
import io.micronaut.scheduling.TaskExecutors;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Controller("/payment-service/internal/pools")
@Requires(property="features.tip-pools-enabled",value="true")
@Secured(SecurityRule.IS_ANONYMOUS)
@ExecuteOn(TaskExecutors.BLOCKING)
public class PoolDissolutionController {
    private final PoolCheckoutGate gate;private final String expected;
    public PoolDissolutionController(PoolCheckoutGate gate,@Value("${internal.api-key}") String expected){this.gate=gate;this.expected=expected;}
    @Post("/{poolId}/freeze") public HttpResponse<?> freeze(UUID poolId,@Header("X-Internal-Secret") @Nullable String secret){
        if(expected==null||expected.isBlank()||secret==null||!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),secret.getBytes(StandardCharsets.UTF_8)))return HttpResponse.unauthorized();
        boolean safe=gate.freeze(poolId);
        return HttpResponse.ok(Map.of("safeToDissolve",safe));
    }
}
