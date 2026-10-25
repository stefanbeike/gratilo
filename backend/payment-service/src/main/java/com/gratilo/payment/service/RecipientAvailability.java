package com.gratilo.payment.service;

import io.micronaut.context.annotation.Value;
import io.micronaut.json.JsonMapper;
import jakarta.inject.Singleton;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/** Checks the authoritative user profile even when checkout is called directly. */
@Singleton
public class RecipientAvailability {
    private final String base;
    public RecipientAvailability(@Value("${pool.user-service-url:http://localhost:8081}") String base) {
        this.base = base.replaceAll("/$", "");
    }
    public void requireEnabled(UUID recipient) throws Exception { require(recipient, "paymentEnabled"); }
    public void requirePoolMemberEnabled(UUID recipient) throws Exception { require(recipient, "poolParticipationEnabled"); }
    private void require(UUID recipient, String flag) throws Exception {
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create(base + "/user-service/users/public/" + recipient)).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) throw new NotEnabledException();
        // A transient user-service failure is not proof that the recipient has
        // disabled payments. Preserve UNKNOWN so callers can fail closed without
        // presenting a false Stripe restriction.
        if (response.statusCode() != 200) throw new IllegalStateException("Recipient status could not be verified");
        var profile = JsonMapper.createDefault().readValue(response.body(), Map.class);
        if (!recipient.toString().equals(profile.get("userId")))
            throw new IllegalStateException("Recipient status response did not match the requested user");
        if (!Boolean.TRUE.equals(profile.get(flag))) throw new NotEnabledException();
    }

    public static final class NotEnabledException extends IllegalStateException {
        public NotEnabledException() { super("Recipient has paused payments"); }
    }
}
