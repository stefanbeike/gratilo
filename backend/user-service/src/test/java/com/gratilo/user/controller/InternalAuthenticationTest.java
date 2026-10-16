package com.gratilo.user.controller;

import com.gratilo.user.api.UserRequests;
import com.gratilo.user.service.EmailSendService;
import com.gratilo.user.service.PoolService;
import com.gratilo.user.security.InternalSecret;
import io.micronaut.http.HttpStatus;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InternalAuthenticationTest {
    @Test void missingEmptyAndWrongHeadersNeverSendMailOrReadPools() {
        var mail = mock(EmailSendService.class);
        var pools = mock(PoolService.class);
        var email = new EmailController(mail, "unit-test-only");
        var pool = new PoolDistributionController(pools, "unit-test-only");
        var request = new UserRequests.SendEmail("test@example.invalid", "Subject", "Body");
        for (String header : new String[]{null, "", " ", "wrong"}) {
            assertEquals(HttpStatus.UNAUTHORIZED, email.send(request, header).getStatus());
            assertEquals(HttpStatus.UNAUTHORIZED, email.sendGeneral(request, header).getStatus());
            assertEquals(HttpStatus.UNAUTHORIZED, pool.distribution(UUID.randomUUID(), header).getStatus());
        }
        verifyNoInteractions(mail, pools);
        assertFalse(InternalSecret.matches("", ""));
    }
    @Test void validHeaderCanReadPool() {
        var pools = mock(PoolService.class);
        UUID owner = UUID.randomUUID();
        assertEquals(HttpStatus.NOT_FOUND, new PoolDistributionController(pools, "unit-test-only").distribution(owner, "unit-test-only").getStatus());
        verify(pools).distribution(owner);
    }
}
