package com.gratilo.user.service;

import jakarta.inject.Singleton;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;

/** Bounded, per-instance mail budget. No raw addresses are retained or logged. */
@Singleton
public class ContactRateLimiter {
    private static final long WINDOW = 15L * 60 * 1_000_000_000;
    private final LongSupplier clock;
    private final Map<String, Integer> counts = new HashMap<>();
    private long started;
    private int total;

    public ContactRateLimiter() { this(System::nanoTime); }
    ContactRateLimiter(LongSupplier clock) { this.clock = clock; this.started = clock.getAsLong(); }

    public synchronized boolean acquire(String email) {
        long now = clock.getAsLong();
        if (now - started >= WINDOW) { started = now; total = 0; counts.clear(); }
        String key = digest(email.trim().toLowerCase(Locale.ROOT));
        if (total >= 30 || counts.getOrDefault(key, 0) >= 3) return false;
        total++;
        counts.merge(key, 1, Integer::sum);
        return true;
    }

    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
