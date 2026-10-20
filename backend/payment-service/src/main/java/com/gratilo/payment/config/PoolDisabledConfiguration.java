package com.gratilo.payment.config;

import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Value;

/** Pool endpoints are gated separately; enabling the feature does not bypass payment checks. */
@Context
public class PoolDisabledConfiguration {
    public PoolDisabledConfiguration(@Value("${features.tip-pools-enabled:false}") boolean enabled) {
        // No startup veto: only the explicit test-mode deployment may enable this flag.
    }
}
