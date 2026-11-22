package com.gratilo.observability;

import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Set;

@Factory
@io.micronaut.context.annotation.Requires(property = "observability.enabled", value = "true")
final class CommonTags {
    @Singleton
    MeterFilter deploymentTags(@Value("${micronaut.application.name:unknown}") String service,
                               @Value("${observability.environment:local}") String environment) {
        String scope = Set.of("local", "demo", "staging", "production").contains(environment) ? environment : "unknown";
        return MeterFilter.commonTags(List.of(Tag.of("service", service), Tag.of("environment", scope)));
    }
}
