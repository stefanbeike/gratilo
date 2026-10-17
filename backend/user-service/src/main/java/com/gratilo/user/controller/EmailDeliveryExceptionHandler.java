package com.gratilo.user.controller;

import io.micronaut.email.EmailException;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import jakarta.inject.Singleton;
import jakarta.mail.AuthenticationFailedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/** Maps mail failures after transactional interceptors have rolled back the request. */
@Produces
@Singleton
public class EmailDeliveryExceptionHandler implements ExceptionHandler<EmailException, HttpResponse<Map<String, String>>> {
    private static final Logger LOG = LoggerFactory.getLogger(EmailDeliveryExceptionHandler.class);

    @Override
    public HttpResponse<Map<String, String>> handle(HttpRequest request, EmailException exception) {
        // Never log provider messages, exception stack traces, recipients or verification links.
        LOG.warn("Email delivery unavailable; category={}", category(exception));
        return HttpResponse.<Map<String, String>>status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Cache-Control", "no-store")
                .body(Map.of(
                        "code", "EMAIL_DELIVERY_UNAVAILABLE",
                        "message", "Der E-Mail-Versand ist vorübergehend nicht verfügbar. Bitte versuche es später erneut."));
    }

    private static String category(Throwable failure) {
        for (int depth = 0; failure != null && depth < 16; depth++, failure = failure.getCause()) {
            if (failure instanceof AuthenticationFailedException) {
                return "SMTP_AUTHENTICATION";
            }
        }
        return "DELIVERY_FAILURE";
    }
}
