package com.gratilo.user.service;

import io.micronaut.context.annotation.Value;
import io.micronaut.email.BodyType;
import io.micronaut.email.Email;
import io.micronaut.email.EmailSender;
import jakarta.inject.Singleton;

@Singleton
public class EmailSendService {
    private final EmailSender<Email.Builder, ?> emailSender;
    private final String fromAddress;
    private final String appName;

    public EmailSendService(
            EmailSender<Email.Builder, ?> emailSender,
            @Value("${micronaut.email.from.email:noreply@gratilo.local}") String fromAddress,
            @Value("${app.name:Gratilo}") String appName
    ) {
        this.emailSender = emailSender;
        this.fromAddress = fromAddress;
        this.appName = appName;
    }

    public void sendVerificationMail(String to, String link) {
        sendText(to, "E-Mail bestätigen – " + appName, """
                Hallo,

                bitte bestätige deine E-Mail-Adresse für %s:
                %s

                Der Link ist 30 Minuten gültig.
                """.formatted(appName, link));
    }

    public void sendPasswordResetMail(String to, String link) {
        sendText(to, "Passwort zurücksetzen – " + appName, """
                Hallo,

                über diesen Link kannst du dein Passwort für %s zurücksetzen:
                %s

                Der Link ist 60 Minuten gültig. Falls du das nicht angefordert hast, ignoriere diese E-Mail.
                """.formatted(appName, link));
    }

    public void sendText(String to, String subject, String text) {
        emailSender.send(Email.builder()
                .from(fromAddress)
                .to(to)
                .subject(subject)
                .body(text, BodyType.TEXT));
    }
}
