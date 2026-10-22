package com.gratilo.user.service;

import io.micronaut.transaction.annotation.TransactionalEventListener;
import jakarta.inject.Singleton;
import java.util.UUID;
import org.slf4j.LoggerFactory;

@Singleton
public class RegistrationNotificationService {
    private final EmailSendService mail;

    public RegistrationNotificationService(EmailSendService mail) {
        this.mail = mail;
    }

    public record Registered(UUID userId, String email) { }

    @TransactionalEventListener(TransactionalEventListener.TransactionPhase.AFTER_COMMIT)
    public void registered(Registered event) {
        try {
            mail.sendGeneralText("info@gratilo.eu", "Neue Registrierung bei Gratilo", """
                    Ein neuer Nutzer hat sich bei Gratilo registriert.

                    E-Mail-Adresse: %s
                    Benutzer-ID: %s

                    Die Bestätigung der E-Mail-Adresse kann noch ausstehen.
                    """.formatted(event.email(), event.userId()));
        } catch (RuntimeException failure) {
            // Registration is already committed; never fail it because of an admin email.
            LoggerFactory.getLogger(getClass()).warn(
                    "Registration notification failed user={} category={}",
                    event.userId(), failure.getClass().getSimpleName());
        }
    }
}
