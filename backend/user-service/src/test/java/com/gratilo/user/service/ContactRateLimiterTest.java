package com.gratilo.user.service;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ContactRateLimiterTest {
    @Test void normalizesAddressesAndExpiresTheBudget() {
        var now = new AtomicLong(0);
        var limiter = new ContactRateLimiter(now::get);
        assertTrue(limiter.acquire("Alex@example.com"));
        assertTrue(limiter.acquire(" alex@example.com "));
        assertTrue(limiter.acquire("ALEX@EXAMPLE.COM"));
        assertFalse(limiter.acquire("alex@example.com"));
        now.set(15L * 60 * 1_000_000_000);
        assertTrue(limiter.acquire("alex@example.com"));
    }
    @Test void capsTotalAttemptsEvenWithChangingAddresses() {
        var limiter = new ContactRateLimiter(() -> 0L);
        for (int i = 0; i < 30; i++) assertTrue(limiter.acquire("person" + i + "@example.com"));
        assertFalse(limiter.acquire("another@example.com"));
    }
}
