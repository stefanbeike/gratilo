package com.gratilo.user.controller;

import com.gratilo.user.api.UserRequests;
import com.gratilo.user.service.EmailSendService;
import io.micronaut.http.HttpStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class EmailControllerTest {
    @Test
    void rejectsInvalidInternalSecret() {
        EmailSendService service = mock(EmailSendService.class);
        EmailController controller = new EmailController(service, "internal-secret");

        assertEquals(HttpStatus.UNAUTHORIZED, controller.send(
                new UserRequests.SendEmail("user@example.com", "Subject", "Body"), "wrong").getStatus());
        verifyNoInteractions(service);
    }

    @Test
    void sendsEmailWithValidInternalSecret() {
        EmailSendService service = mock(EmailSendService.class);
        EmailController controller = new EmailController(service, "internal-secret");

        assertEquals(HttpStatus.OK, controller.send(
                new UserRequests.SendEmail("user@example.com", "Subject", "Body"), "internal-secret").getStatus());
        verify(service).sendText("user@example.com", "Subject", "Body");
    }
}
