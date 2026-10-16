package com.gratilo.user.controller;

import com.gratilo.user.api.UserRequests;
import com.gratilo.user.service.PasswordResetService;
import io.micronaut.http.HttpStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class PasswordResetControllerTest {
    @Test
    void requestsResetWithoutRevealingWhetherEmailExists() {
        PasswordResetService service = mock(PasswordResetService.class);
        PasswordResetController controller = new PasswordResetController(service);

        var response = controller.request(new UserRequests.PasswordResetRequest("user@example.com"));

        assertEquals(HttpStatus.OK, response.getStatus());
        verify(service).createPasswordResetToken("user@example.com");
    }

    @Test
    void confirmsReset() {
        PasswordResetService service = mock(PasswordResetService.class);
        PasswordResetController controller = new PasswordResetController(service);

        var response = controller.confirm(new UserRequests.PasswordResetConfirm("token", "New!Password1"));

        assertEquals(HttpStatus.OK, response.getStatus());
        verify(service).resetPassword("token", "New!Password1");
    }
}
