package com.gratilo.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micronaut.context.BeanContext;
import io.micronaut.context.annotation.Value;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Singleton
public final class ObservationEvents {
    private final BeanContext context;
    private final boolean enabled;
    private final String service;
    private final String log;

    private final ThreadPoolExecutor logWriter = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(512), task -> Thread.ofPlatform().name("observability-event-log").daemon().unstarted(task));

    public ObservationEvents(BeanContext context, @Value("${observability.enabled:false}") boolean enabled,
                             @Value("${micronaut.application.name:unknown}") String service,
                             @Value("${observability.event-log:}") String log) {
        this.context = context;
        this.enabled = enabled;
        this.service = service;
        this.log = log;
    }

    public enum Event {
        WEBHOOK_SIGNATURE_INVALID, STRIPE_MODE_MISMATCH, AUDIT_WEBHOOK_DUPLICATE,
        CHECKOUT_IDEMPOTENCY_CONFLICT, STRIPE_SESSION_CREATED, RECONCILIATION_FAILURE,
        AUDIT_REVIEW_REQUIRED, PAYOUT_SETUP_FAILURE, TRANSFER_RETRY
    }

    public static void record(ObservationEvents events, Event event) {
        if (events != null && events.enabled) {
            String name = event.name().toLowerCase(java.util.Locale.ROOT);
            events.context.getBean(MeterRegistry.class).counter("gratilo.observation.events", "event", name).increment();
            if (event != Event.STRIPE_SESSION_CREATED) events.log(name, "warning");
        }
    }

    void operation(String operation, String outcome, long start) {
        if (!enabled) return;
        elapsed("gratilo.operation.duration", start, "operation", operation, "outcome", outcome);
        if (Set.of("5xx", "exception", "false").contains(outcome)) log(operation, outcome);
    }

    public static HttpResponse<String> send(ObservationEvents events, String dependency, HttpRequest request)
            throws IOException, InterruptedException {
        long start = System.nanoTime();
        String outcome = "transport_error";
        try {
            var result = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            outcome = result.statusCode() == 429 ? "rate_limit" : result.statusCode() / 100 + "xx";
            return result;
        } finally {
            if (events != null && events.enabled) {
                events.elapsed("gratilo.dependency.duration", start, "dependency", dependency, "outcome", outcome);
            }
        }
    }

    private void elapsed(String metric, long start, String... tags) {
        Timer.builder(metric).tags(tags)
                .serviceLevelObjectives(Duration.ofMillis(25), Duration.ofMillis(100), Duration.ofMillis(250),
                        Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(5),
                        Duration.ofSeconds(15), Duration.ofSeconds(30), Duration.ofSeconds(60))
                .register(context.getBean(MeterRegistry.class)).record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
    }

    void security(String category) {
        log("http_security_response", category);
    }

    @PreDestroy
    void stop() {
        logWriter.shutdown();
    }

    private void log(String event, String outcome) {
        if (log.isBlank()) return;
        // Only application literals enter the event file. Queue pressure never blocks a request.
        String line = "{\"timestamp\":\"" + Instant.now() + "\",\"service\":\"" + service
                + "\",\"event\":\"" + event + "\",\"outcome\":\"" + outcome + "\"}\n";
        try {
            logWriter.execute(() -> writeLine(line));
        } catch (RejectedExecutionException failure) {
            logFailure();
        }
    }

    private void writeLine(String line) {
        try {
            Path file = Path.of(log);
            if (Files.exists(file) && Files.size(file) >= 5 * 1024 * 1024) {
                Files.move(file, file.resolveSibling(file.getFileName() + ".1"), StandardCopyOption.REPLACE_EXISTING);
            }
            Files.writeString(file, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException failure) {
            logFailure();
        }
    }

    private void logFailure() {
        context.findBean(MeterRegistry.class).ifPresent(registry ->
                registry.counter("gratilo.observation.log.failures").increment());
    }
}
