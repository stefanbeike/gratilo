package com.gratilo.user.controller;

import com.gratilo.user.api.UserResponses;
import com.gratilo.user.service.ContactRateLimiter;
import com.gratilo.user.service.EmailSendService;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.serde.annotation.Serdeable;
import java.util.Set;

@Secured(SecurityRule.IS_ANONYMOUS)
@Controller("/user-service/contact")
@ExecuteOn(TaskExecutors.BLOCKING)
public class ContactController {
    private static final Set<String> TOPICS = Set.of("Allgemeine Anfrage", "Einzelkonto", "Pool für unser Team",
            "NFC-Karte mit QR-Code", "Tischaufsteller", "Button mit QR-Code", "QR-Aufkleber");
    private final EmailSendService mail;
    private final ContactRateLimiter limiter;

    public ContactController(EmailSendService mail, ContactRateLimiter limiter) {
        this.mail = mail;
        this.limiter = limiter;
    }

    @Serdeable
    public record Request(String name, String email, String topic, String message, String website) {}

    @Post(consumes = "application/json")
    public HttpResponse<UserResponses.Message> send(@Body Request request) {
        if (request == null || !singleLine(request.name(), 100) || !singleLine(request.email(), 254)
                || !request.email().trim().matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")
                || request.topic() == null || !TOPICS.contains(request.topic())
                || request.message() == null || request.message().isBlank() || request.message().length() > 2000
                || (request.website() != null && !request.website().isEmpty())) {
            return response(HttpStatus.BAD_REQUEST, "Bitte prüfe deine Angaben.");
        }
        if (!limiter.acquire(request.email())) {
            return response(HttpStatus.TOO_MANY_REQUESTS, "Zu viele Anfragen. Bitte versuche es in 15 Minuten erneut.")
                    .header("Retry-After", "900");
        }
        // Fixed recipient and sender: this public endpoint cannot be used as an open mail relay.
        // Do not put visitor input in mail headers or log message contents.
        mail.sendGeneralText("info@gratilo.eu", "Gratilo Kontakt: " + request.topic(),
                "Name: " + request.name().trim() + "\nAntwort an: " + request.email().trim()
                        + "\nAnliegen: " + request.topic() + "\n\n" + request.message().trim());
        return response(HttpStatus.OK, "Danke! Deine Nachricht wurde zum Versand angenommen.");
    }

    private static boolean singleLine(String value, int max) {
        return value != null && !value.isBlank() && value.length() <= max
                && value.chars().noneMatch(Character::isISOControl);
    }

    private static MutableHttpResponse<UserResponses.Message> response(HttpStatus status, String message) {
        return HttpResponse.<UserResponses.Message>status(status).header("Cache-Control", "no-store")
                .body(new UserResponses.Message(message));
    }
}
