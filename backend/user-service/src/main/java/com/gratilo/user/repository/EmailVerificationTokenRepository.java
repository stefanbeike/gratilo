package com.gratilo.user.repository;

import com.gratilo.user.domain.EmailVerificationTokenEntity;
import io.micronaut.data.annotation.Repository;
import io.micronaut.data.repository.CrudRepository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface EmailVerificationTokenRepository extends CrudRepository<EmailVerificationTokenEntity, UUID> {
    Optional<EmailVerificationTokenEntity> findByToken(String token);

    Optional<EmailVerificationTokenEntity> findFirstByUserIdOrderByExpiresAtDesc(UUID userId);

    long deleteByUserId(UUID userId);
}
