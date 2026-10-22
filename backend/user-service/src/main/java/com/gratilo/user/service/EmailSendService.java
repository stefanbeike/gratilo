package com.gratilo.user.service;

import io.micronaut.context.annotation.Value;
import io.micronaut.email.BodyType;
import io.micronaut.email.Email;
import io.micronaut.email.EmailSender;
import io.micronaut.email.Contact;
import jakarta.inject.Singleton;

@Singleton
public class EmailSendService {
    private final EmailSender<Email.Builder, ?> emailSender;
    private final String fromAddress;
    private final String generalFromAddress;
    private final String fromName;
    private final String appName;

    public EmailSendService(
            EmailSender<Email.Builder, ?> emailSender,
            @Value("${micronaut.email.from.email:noreply@gratilo.local}") String fromAddress,
            @Value("${app.email.general-from:info@gratilo.local}") String generalFromAddress,
            @Value("${micronaut.email.from.name:Gratilo}") String fromName,
            @Value("${app.name:Gratilo}") String appName
    ) {
        this.emailSender = emailSender;
        this.fromAddress = fromAddress;
        this.generalFromAddress = generalFromAddress;
        this.fromName = fromName;
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
        sendFrom(fromAddress, to, subject, text);
    }

    /** One recipient and multipart/alternative; shares the existing SMTP/SES transport. */
    public void sendAlternative(String to,String subject,String text,String html) {
        emailSender.send(Email.builder().from(new Contact(fromAddress,fromName)).to(to).subject(subject).body(html,text));
    }

    public void sendGeneralText(String to, String subject, String text) {
        sendFrom(generalFromAddress, to, subject, text);
    }

    private void sendFrom(String from, String to, String subject, String text) {
        emailSender.send(Email.builder()
                .from(new Contact(from, fromName))
                .to(to)
                .subject(subject)
                .body(text, BodyType.TEXT));
    }
}
