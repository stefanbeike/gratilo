package com.gratilo.payment.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class RecipientAvailabilityTest {
    @Test void rejectsPausedMissingAndMismatchedProfiles() throws Exception {
        UUID recipient = UUID.randomUUID();
        var response = new AtomicReference<>("{\"userId\":\"" + recipient + "\",\"paymentEnabled\":true}");
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/user-service/users/public/" + recipient, exchange -> {
            byte[] body = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(body.length == 0 ? 404 : 200, body.length);
            try (var stream = exchange.getResponseBody()) { stream.write(body); }
        });
        server.start();
        try {
            var service = new RecipientAvailability("http://127.0.0.1:" + server.getAddress().getPort());
            assertDoesNotThrow(() -> service.requireEnabled(recipient));
            for (String body : new String[]{"", "{}", "{\"userId\":\"" + recipient + "\",\"paymentEnabled\":false}",
                    "{\"userId\":\"" + UUID.randomUUID() + "\",\"paymentEnabled\":true}"}) {
                response.set(body);
                assertThrows(IllegalStateException.class, () -> service.requireEnabled(recipient));
            }
        } finally { server.stop(0); }
    }
}
