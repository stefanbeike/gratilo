package com.gratilo.user.config;

import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Value;

/** Kept as a compatibility bean; pool routes are independently guarded by the feature flag. */
@Context
public class PoolDisabledConfiguration {
    public PoolDisabledConfiguration(@Value("${features.tip-pools-enabled:false}") boolean enabled) {
        // Enabling the feature exposes only authenticated pool management routes.
    }
}
