package com.gratilo.user.controller;

import com.gratilo.user.service.ContactRateLimiter;
import com.gratilo.user.service.EmailSendService;
import io.micronaut.http.HttpStatus;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContactControllerTest {
    private ContactController.Request request(String name, String email, String topic, String message, String website) {
        return new ContactController.Request(name, email, topic, message, website);
    }

    @Test void sendsOnlyToTheFixedTeamInbox() {
        var mail = mock(EmailSendService.class);
        var controller = new ContactController(mail, new ContactRateLimiter());
        var response = controller.send(request(" Alex ", "alex@example.com", "Pool für unser Team", "Hallo Team!", ""));
        assertEquals(HttpStatus.OK, response.getStatus());
        verify(mail).sendGeneralText(eq("info@gratilo.eu"), eq("Gratilo Kontakt: Pool für unser Team"),
                eq("Name: Alex\nAntwort an: alex@example.com\nAnliegen: Pool für unser Team\n\nHallo Team!"));
        verifyNoMoreInteractions(mail);
    }

    @Test void invalidInputAndHoneypotNeverSendMail() {
        var mail = mock(EmailSendService.class);
        var controller = new ContactController(mail, new ContactRateLimiter());
        var invalid = java.util.Arrays.asList(null,
                request("", "a@example.com", "Einzelkonto", "Hallo", ""),
                request("Alex\r\nBcc: other@example.com", "a@example.com", "Einzelkonto", "Hallo", ""),
                request("Alex", "not-an-email", "Einzelkonto", "Hallo", ""),
                request("Alex", "a@example.com", "Injected topic", "Hallo", ""),
                request("Alex", "a@example.com", "Einzelkonto", " ", ""),
                request("Alex", "a@example.com", "Einzelkonto", "a".repeat(2001), ""),
                request("Alex", "a@example.com", "Einzelkonto", "Hallo", "spam.example"));
        for (var item : invalid) assertEquals(HttpStatus.BAD_REQUEST, controller.send(item).getStatus());
        verifyNoInteractions(mail);
    }

    @Test void throttlesAndDoesNotPretendToSendOnTransportFailure() {
        var mail = mock(EmailSendService.class);
        var controller = new ContactController(mail, new ContactRateLimiter());
        var item = request("Alex", "a@example.com", "Einzelkonto", "Hallo", "");
        doThrow(new IllegalStateException("mail unavailable")).when(mail).sendGeneralText(anyString(), anyString(), anyString());
        for (int i = 0; i < 3; i++) assertThrows(IllegalStateException.class, () -> controller.send(item));
        var response = controller.send(item);
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatus());
        assertEquals("900", response.header("Retry-After"));
        verify(mail, times(3)).sendGeneralText(anyString(), anyString(), anyString());
    }
}
