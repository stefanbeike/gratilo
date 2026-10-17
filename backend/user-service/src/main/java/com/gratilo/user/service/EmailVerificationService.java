package com.gratilo.user.service;

import com.gratilo.user.domain.EmailVerificationTokenEntity;
import com.gratilo.user.domain.UserEntity;
import com.gratilo.user.repository.EmailVerificationTokenRepository;
import com.gratilo.user.repository.UserRepository;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;

@Singleton
public class EmailVerificationService {
    private static final int TOKEN_VALIDITY_MINUTES = 30;
    private static final int RESEND_COOLDOWN_SECONDS = 60;
    private final EmailVerificationTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final EmailSendService emailSendService;
    private final String frontendBaseUrl;

    public EmailVerificationService(
            EmailVerificationTokenRepository tokenRepository,
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
    public void createAndSend(UserEntity user) {
        tokenRepository.deleteByUserId(user.getId());
        EmailVerificationTokenEntity token = new EmailVerificationTokenEntity();
        token.setUser(user);
        token.setToken(randomToken());
        token.setExpiresAt(Instant.now().plus(TOKEN_VALIDITY_MINUTES, ChronoUnit.MINUTES));
        tokenRepository.save(token);
        emailSendService.sendVerificationMail(user.getEmail(),
                frontendBaseUrl + "/auth/verify?token=" + token.getToken());
    }

    @Transactional
    public void resend(UUID userId) {
        // Serialize concurrent resend requests and recheck account state under the lock.
        UserEntity user = userRepository.findByIdForUpdate(userId)
                .filter(value -> value.getDeletedAt() == null && !value.isEmailVerified())
                .orElse(null);
        if (user == null) {
            return;
        }
        // Existing tokens have a 30-minute lifetime. Keep a 60-second resend cooldown
        // without changing the schema or invalidating a token when delivery fails.
        boolean recentlySent = tokenRepository.findFirstByUserIdOrderByExpiresAtDesc(userId)
                .filter(token -> !token.isUsed())
                .map(token -> token.getExpiresAt().minus(TOKEN_VALIDITY_MINUTES, ChronoUnit.MINUTES)
                        .plus(RESEND_COOLDOWN_SECONDS, ChronoUnit.SECONDS).isAfter(Instant.now()))
                .orElse(false);
        if (!recentlySent) {
            createAndSend(user);
        }
    }

    @Transactional
    public boolean verify(String rawToken) {
        return tokenRepository.findByToken(rawToken)
                .filter(token -> !token.isUsed())
                .filter(token -> token.getExpiresAt().isAfter(Instant.now()))
                .map(token -> {
                    UserEntity user = token.getUser();
                    user.setEmailVerified(true);
                    userRepository.update(user);
                    tokenRepository.delete(token);
                    return true;
                })
                .orElse(false);
    }

    static String randomToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
