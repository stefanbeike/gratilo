package com.gratilo.user.controller;

import com.gratilo.user.api.UserRequests;
import com.gratilo.user.domain.UserEntity;
import com.gratilo.user.repository.UserRepository;
import com.gratilo.user.service.EmailVerificationService;
import io.micronaut.http.HttpStatus;
import io.micronaut.security.authentication.Authentication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UserControllerTest {
    private UserRepository users;
    private EmailVerificationService verification;
    private UserController controller;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        verification = mock(EmailVerificationService.class);
        controller = new UserController(users, verification);
    }

    @Test
    void registrationCreatesUnverifiedUserAndSendsVerification() throws Exception {
        var imageBytes = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(256, 256, java.awt.image.BufferedImage.TYPE_INT_RGB), "jpeg", imageBytes);
        String profileImage = "data:image/jpeg;base64," + java.util.Base64.getEncoder().encodeToString(imageBytes.toByteArray());
        UUID id = UUID.randomUUID();
        when(users.existsByEmail("user@example.com")).thenReturn(false);
        when(users.save(any(UserEntity.class))).thenAnswer(invocation -> {
            UserEntity user = invocation.getArgument(0);
            user.setId(id);
            return user;
        });

        var response = controller.register(new UserRequests.Register(
                "User@example.com", "Secret!1", "Secret!1", false,
                "Ada", "Lovelace", null, null, null, "DE", null, "agb-v1", "privacy-v1", profileImage));

        assertEquals(HttpStatus.CREATED, response.getStatus());
        verify(verification).createAndSend(org.mockito.ArgumentMatchers.argThat(user ->
                profileImage.equals(user.getProfileImage()) && profileImage.equals(com.gratilo.user.api.UserResponses.Profile.from(user).profileImage())));
    }

    @Test
    void profileRequiresAuthenticationAndReturnsOwnData() {
        UUID id = UUID.randomUUID();
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setEmail("user@example.com");
        when(users.findById(id)).thenReturn(Optional.of(user));

        var response = controller.showProfile(Authentication.build(id.toString(), Map.of()));

        assertEquals(HttpStatus.OK, response.getStatus());
    }

    @Test
    void profileUpdateRejectsWrongCurrentPassword() {
        UUID id = UUID.randomUUID();
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setPasswordHash(org.mindrot.jbcrypt.BCrypt.hashpw("Correct!1", org.mindrot.jbcrypt.BCrypt.gensalt()));
        when(users.findById(id)).thenReturn(Optional.of(user));

        var response = controller.editProfile(
                new UserRequests.ProfileUpdate(null, null, null, null, null, null, null, null,
                        "Wrong!1", null, null, null),
                Authentication.build(id.toString(), Map.of()));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatus());
        verify(users, never()).update(any());
    }
}
