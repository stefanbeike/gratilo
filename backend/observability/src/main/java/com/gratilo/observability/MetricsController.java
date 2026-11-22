package com.gratilo.observability;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Header;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.core.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Controller(MetricsController.PATH)
@Requires(property = "observability.enabled", value = "true")
@Secured(SecurityRule.IS_ANONYMOUS)
final class MetricsController {
    static final String PATH = "/internal/observability/metrics";
    private final PrometheusMeterRegistry registry;
    private final String token;

    MetricsController(PrometheusMeterRegistry registry, @Value("${observability.scrape-token}") String token) {
        this.registry = registry;
        this.token = token;
    }

    @Get(produces = "text/plain")
    HttpResponse<String> scrape(@Header(HttpHeaders.AUTHORIZATION) @Nullable String authorization) {
        if (token.length() < 32 || authorization == null || !MessageDigest.isEqual(
                ("Bearer " + token).getBytes(StandardCharsets.UTF_8),
                authorization.getBytes(StandardCharsets.UTF_8))) {
            return HttpResponse.notFound();
        }
        return HttpResponse.ok(registry.scrape()).header(HttpHeaders.CONTENT_TYPE, "text/plain; version=0.0.4; charset=utf-8");
    }
}
