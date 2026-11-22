package com.gratilo.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.web.router.RouteAttributes;
import io.micronaut.web.router.UriRouteMatch;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.filter.HttpServerFilter;
import io.micronaut.http.filter.ServerFilterChain;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

@Filter("/**")
@Requires(property = "observability.enabled", value = "true")
final class HttpMetrics implements HttpServerFilter {
    private final MeterRegistry registry;
    private final AtomicInteger active;
    private final ObservationEvents events;

    HttpMetrics(MeterRegistry registry, ObservationEvents events, @Value("${observability.build}") String version) {
        this.registry = registry;
        this.events = events;
        active = registry.gauge("gratilo.http.server.active", new AtomicInteger());
        registry.gauge("gratilo.build.info", java.util.List.of(io.micrometer.core.instrument.Tag.of("version",
                version.matches("[A-Za-z0-9._-]{1,80}") ? version : "unknown")), this, ignored -> 1);
    }

    @Override
    public int getOrder() {
        return -1000;
    }

    @Override
    public Publisher<MutableHttpResponse<?>> doFilter(HttpRequest<?> request, ServerFilterChain chain) {
        if (MetricsController.PATH.equals(request.getPath())) {
            return chain.proceed(request);
        }
        return Flux.defer(() -> {
            active.incrementAndGet();
            var sample = Timer.start(registry);
            return Flux.from(chain.proceed(request))
                    .doOnNext(response -> record(request, sample, response.code()))
                    .doOnError(error -> record(request, sample, 500))
                    .doFinally(signal -> active.decrementAndGet());
        });
    }

    private void record(HttpRequest<?> request, Timer.Sample sample, int code) {
        // Only use the router-provided template. Never fall back to the request path.
        String route = RouteAttributes.getRouteMatch(request).filter(UriRouteMatch.class::isInstance)
                .map(match -> ((UriRouteMatch<?, ?>) match).getRouteInfo().getUriMatchTemplate().toString()).orElse("UNMATCHED");
        String method = request.getMethod() == HttpMethod.CUSTOM ? "OTHER" : request.getMethod().name();
        String category = switch (code) {
            case 401 -> route.equals("/user-service/auth/login") ? "invalid_credentials" : "unauthenticated";
            case 403 -> route.equals("/user-service/auth/login") ? "login_forbidden" : "forbidden";
            case 429 -> "rate_limit";
            default -> null;
        };
        if (category != null) {
            registry.counter("gratilo.http.security.responses", "category", category).increment();
            events.security(category);
        }
        sample.stop(Timer.builder("gratilo.http.server.requests")
                .tags("endpoint_template", route, "method", method, "status_class", (code / 100) + "xx")
                .serviceLevelObjectives(Duration.ofMillis(5), Duration.ofMillis(25), Duration.ofMillis(100),
                        Duration.ofMillis(250), Duration.ofMillis(500), Duration.ofSeconds(1),
                        Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(15), Duration.ofSeconds(60))
                .register(registry));
    }
}
