package com.gratilo.user.repository;

import com.gratilo.user.domain.PasswordResetTokenEntity;
import io.micronaut.data.annotation.Query;
import io.micronaut.data.annotation.Repository;
import io.micronaut.data.repository.CrudRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PasswordResetTokenRepository extends CrudRepository<PasswordResetTokenEntity, UUID> {
    @Query(value = "DELETE FROM user_mgmt.password_reset_tokens WHERE user_id = :userId AND used = false", nativeQuery = true)
    void deactivateOldTokens(UUID userId);

    Optional<PasswordResetTokenEntity> findFirstByTokenHashAndUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc(
            String tokenHash,
            Instant now
    );
}
