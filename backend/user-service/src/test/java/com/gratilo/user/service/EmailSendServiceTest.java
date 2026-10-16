package com.gratilo.user.service;

import io.micronaut.email.BodyType;
import io.micronaut.email.Email;
import io.micronaut.email.EmailSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EmailSendServiceTest {
    private EmailSender<Email.Builder, ?> sender;
    private EmailSendService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        sender = mock(EmailSender.class);
        service = new EmailSendService(sender, "noreply@gratilo.eu", "info@gratilo.eu", "Gratilo", "Gratilo");
    }

    @Test
    void verificationUsesSystemSenderAndPreservesLinkAndGermanText() {
        String link = "https://gratilo.eu/auth/verify?token=test-token";
        service.sendVerificationMail("recipient@example.invalid", link);
        Email email = sent();
        assertSystemSender(email);
        assertEquals("E-Mail bestätigen – Gratilo", email.getSubject());
        assertTrue(email.getBody().get(BodyType.TEXT).orElseThrow().contains(link));
        assertTrue(email.getBody().get(BodyType.TEXT).orElseThrow().contains("30 Minuten"));
        assertEquals("recipient@example.invalid", email.getTo().iterator().next().getEmail());
    }

    @Test
    void passwordResetUsesSystemSender() {
        service.sendPasswordResetMail("recipient@example.invalid", "https://gratilo.eu/reset-password?token=test-token");
        Email email = sent();
        assertSystemSender(email);
        assertEquals("Passwort zurücksetzen – Gratilo", email.getSubject());
        assertTrue(email.getBody().get(BodyType.TEXT).orElseThrow().contains("60 Minuten"));
    }

    @Test
    void existingTextNotificationsRemainSystemEmails() {
        service.sendText("recipient@example.invalid", "Pool-Einladung", "Grüße vom Team");
        Email email = sent();
        assertSystemSender(email);
        assertEquals("Grüße vom Team", email.getBody().get(BodyType.TEXT).orElseThrow());
    }

    @Test
    void generalCommunicationUsesInfoAddress() {
        service.sendGeneralText("recipient@example.invalid", "Deine Anfrage", "Unsere Antwort");
        Email email = sent();
        assertEquals("info@gratilo.eu", email.getFrom().getEmail());
        assertEquals("Gratilo", email.getFrom().getName());
        assertEquals("Deine Anfrage", email.getSubject());
        assertEquals("Unsere Antwort", email.getBody().get(BodyType.TEXT).orElseThrow());
    }

    private void assertSystemSender(Email email) {
        assertEquals("noreply@gratilo.eu", email.getFrom().getEmail());
        assertEquals("Gratilo", email.getFrom().getName());
    }

    private Email sent() {
        var captor = ArgumentCaptor.forClass(Email.Builder.class);
        verify(sender).send(captor.capture());
        verifyNoMoreInteractions(sender);
        return captor.getValue().build();
    }
}
