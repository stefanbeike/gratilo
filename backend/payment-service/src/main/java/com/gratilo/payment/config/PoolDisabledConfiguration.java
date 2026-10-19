package com.gratilo.payment.config;

import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Value;

/** Pooling is not supported in the current individual-tip release. */
@Context
public class PoolDisabledConfiguration {
    public PoolDisabledConfiguration(@Value("${features.tip-pools-enabled:false}") boolean enabled) {
        if (enabled) throw new IllegalStateException("Tip pooling must remain disabled");
    }
}
