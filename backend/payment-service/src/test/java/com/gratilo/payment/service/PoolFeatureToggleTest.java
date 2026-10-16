package com.gratilo.payment.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class PoolFeatureToggleTest {
    @Test
    void skipsDistributionLookupWhenDisabledAndRestoresItWhenEnabled() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID pool = UUID.randomUUID();
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/user-service/internal/pools/" + owner, exchange -> {
            calls.incrementAndGet();
            byte[] body = ("{\"poolId\":\"" + pool + "\",\"poolName\":\"Team\",\"members\":[]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            assertTrue(new PoolDistributionClient(base, "secret", false).find(owner).isEmpty());
            assertEquals(0, calls.get());
            assertEquals(pool, new PoolDistributionClient(base, "secret", true).find(owner).orElseThrow().poolId());
            assertEquals(1, calls.get());
        } finally {
            server.stop(0);
        }
    }
}
