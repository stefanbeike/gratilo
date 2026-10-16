package com.gratilo.user.controller;

import io.micronaut.context.annotation.Value;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import java.util.Map;

@Controller("/user-service/features")
@Secured(SecurityRule.IS_ANONYMOUS)
public class FeatureController {
    private final boolean tipPoolsEnabled;

    public FeatureController(@Value("${features.tip-pools-enabled:false}") boolean tipPoolsEnabled) {
        this.tipPoolsEnabled = tipPoolsEnabled;
    }

    @Get
    public Map<String, Boolean> features() {
        return Map.of("tipPoolsEnabled", tipPoolsEnabled);
    }
}
