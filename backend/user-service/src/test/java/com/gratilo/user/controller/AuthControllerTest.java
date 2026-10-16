package com.gratilo.user.controller;

import com.gratilo.user.api.UserRequests;
import com.gratilo.user.domain.UserEntity;
import com.gratilo.user.repository.UserRepository;
import com.gratilo.user.service.EmailVerificationService;
import com.gratilo.user.service.JwtService;
import io.micronaut.http.HttpStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mindrot.jbcrypt.BCrypt;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class AuthControllerTest {
    private UserRepository users;
    private JwtService jwt;
    private EmailVerificationService verification;
    private AuthController controller;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        jwt = mock(JwtService.class);
        verification = mock(EmailVerificationService.class);
        controller = new AuthController(users, jwt, verification);
    }

    @Test
    void rejectsUnknownCredentials() {
        when(users.findByEmail("user@example.com")).thenReturn(Optional.empty());

        assertEquals(HttpStatus.UNAUTHORIZED,
                controller.login(new UserRequests.Login("user@example.com", "Secret!1")).getStatus());
    }

    @Test
    void rejectsUnverifiedAccount() {
        UserEntity user = user("Secret!1", false);
        when(users.findByEmail(user.getEmail())).thenReturn(Optional.of(user));

        assertEquals(HttpStatus.FORBIDDEN,
                controller.login(new UserRequests.Login(user.getEmail(), "Secret!1")).getStatus());
    }

    @Test
    void returnsBearerTokenForVerifiedAccount() {
        UserEntity user = user("Secret!1", true);
        when(users.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(jwt.generateToken(user)).thenReturn("jwt");

        assertEquals(HttpStatus.OK,
                controller.login(new UserRequests.Login(user.getEmail(), "Secret!1")).getStatus());
        verify(jwt).generateToken(user);
    }

    private static UserEntity user(String password, boolean verified) {
        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail("user@example.com");
        user.setPasswordHash(BCrypt.hashpw(password, BCrypt.gensalt()));
        user.setEmailVerified(verified);
        return user;
    }
}
