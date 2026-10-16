package com.gratilo.user.service;

import com.gratilo.user.domain.PasswordResetTokenEntity;
import com.gratilo.user.repository.PasswordResetTokenRepository;
import com.gratilo.user.repository.UserRepository;
import io.micronaut.context.annotation.Value;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.HttpStatusException;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;
import org.mindrot.jbcrypt.BCrypt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

@Singleton
public class PasswordResetService {
    private final PasswordResetTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final EmailSendService emailSendService;
    private final String frontendBaseUrl;

    public PasswordResetService(
            PasswordResetTokenRepository tokenRepository,
            UserRepository userRepository,
            EmailSendService emailSendService,
            @Value("${frontend.base-url:`http://localhost:3000`}") String frontendBaseUrl
    ) {
        this.tokenRepository = tokenRepository;
        this.userRepository = userRepository;
        this.emailSendService = emailSendService;
        this.frontendBaseUrl = frontendBaseUrl;
    }

    @Transactional
    public void createPasswordResetToken(String email) {
        userRepository.findByEmail(email.toLowerCase()).ifPresent(user -> {
            tokenRepository.deactivateOldTokens(user.getId());
            String rawToken = EmailVerificationService.randomToken();
            PasswordResetTokenEntity token = new PasswordResetTokenEntity();
            token.setUserId(user.getId());
            token.setTokenHash(sha256(rawToken));
            token.setExpiresAt(Instant.now().plus(60, ChronoUnit.MINUTES));
            tokenRepository.save(token);
            emailSendService.sendPasswordResetMail(user.getEmail(),
                    frontendBaseUrl + "/reset-password?token=" + rawToken);
        });
    }

    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        PasswordResetTokenEntity token = tokenRepository
                .findFirstByTokenHashAndUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc(sha256(rawToken), Instant.now())
                .orElseThrow(() -> new HttpStatusException(HttpStatus.BAD_REQUEST, "Token ist ungültig oder abgelaufen"));
        userRepository.updatePassword(token.getUserId(), BCrypt.hashpw(newPassword, BCrypt.gensalt()));
        token.setUsed(true);
        tokenRepository.update(token);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Base64.getEncoder().encodeToString(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 hashing failed", exception);
        }
    }
}
