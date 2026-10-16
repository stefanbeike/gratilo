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

    @Test
    void generalEmailUsesInfoSenderPath() {
        EmailSendService service = mock(EmailSendService.class);
        EmailController controller = new EmailController(service, "internal-secret");
        assertEquals(HttpStatus.OK, controller.sendGeneral(
                new UserRequests.SendEmail("user@example.com", "Subject", "Body"), "internal-secret").getStatus());
        verify(service).sendGeneralText("user@example.com", "Subject", "Body");
        verifyNoMoreInteractions(service);
    }

    @Test
    void generalEmailRejectsMissingOrInvalidInternalSecret() {
        EmailSendService service = mock(EmailSendService.class);
        EmailController controller = new EmailController(service, "internal-secret");
        var request = new UserRequests.SendEmail("user@example.com", "Subject", "Body");
        assertEquals(HttpStatus.UNAUTHORIZED, controller.sendGeneral(request, null).getStatus());
        assertEquals(HttpStatus.UNAUTHORIZED, controller.sendGeneral(request, "wrong").getStatus());
        verifyNoInteractions(service);
    }
}
