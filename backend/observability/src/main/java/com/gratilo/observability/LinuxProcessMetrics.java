package com.gratilo.observability;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Singleton
@Requires(property = "observability.enabled", value = "true")
final class LinuxProcessMetrics implements MeterBinder {
    private static final Path PROC = Path.of("/proc/self/status");
    private static final Path MEMORY = Path.of("/sys/fs/cgroup/memory.current");
    private static final Path CPU = Path.of("/sys/fs/cgroup/cpu.stat");

    @Override
    public void bindTo(MeterRegistry registry) {
        if (Files.isReadable(PROC)) {
            registry.gauge("gratilo.process.resident.bytes", this, ignored -> field(PROC, "VmRSS:", 1024));
        }
        if (Files.isReadable(MEMORY)) {
            registry.gauge("gratilo.cgroup.memory.bytes", this, ignored -> readMemory());
        }
        if (Files.isReadable(CPU)) {
            FunctionCounter.builder("gratilo.cgroup.cpu.usage.seconds", this,
                    ignored -> field(CPU, "usage_usec", 1e-6)).register(registry);
            FunctionCounter.builder("gratilo.cgroup.cpu.throttled.seconds", this,
                    ignored -> field(CPU, "throttled_usec", 1e-6)).register(registry);
        }
    }

    private static double readMemory() {
        try {
            return Long.parseLong(Files.readString(MEMORY).strip());
        } catch (IOException | NumberFormatException ignored) {
            return Double.NaN;
        }
    }

    private static double field(Path file, String key, double multiplier) {
        try (var lines = Files.lines(file)) {
            return lines.map(String::strip).filter(line -> line.startsWith(key + " ") || line.startsWith(key + "\t"))
                    .findFirst().map(line -> Long.parseLong(line.split("\\s+")[1]) * multiplier)
                    .orElse(Double.NaN);
        } catch (IOException | NumberFormatException ignored) {
            return Double.NaN;
        }
    }
}
