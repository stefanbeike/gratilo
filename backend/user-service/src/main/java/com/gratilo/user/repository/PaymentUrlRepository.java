package com.gratilo.user.repository;

import com.gratilo.user.domain.PaymentUrlEntity;
import io.micronaut.data.annotation.Query;
import io.micronaut.data.annotation.Repository;
import io.micronaut.data.repository.CrudRepository;
import java.util.*;

@Repository
public interface PaymentUrlRepository extends CrudRepository<PaymentUrlEntity, Long> {
    @Query(value="SELECT * FROM user_mgmt.payment_url_pool WHERE assigned_at IS NULL AND assigned_user_id IS NULL AND assigned_pool_id IS NULL AND original_pool_id IS NULL AND retired_at IS NULL ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED", nativeQuery=true)
    Optional<PaymentUrlEntity> findAvailableForUpdate();
    Optional<PaymentUrlEntity> findByAssignedUserId(UUID id);
    Optional<PaymentUrlEntity> findByAssignedPoolId(UUID id);
    Optional<PaymentUrlEntity> findByLegacyOwnerId(UUID id);
    Optional<PaymentUrlEntity> findByPublicPath(String path);
    Optional<PaymentUrlEntity> findByPaymentUrl(String paymentUrl);
    long countByAssignedAtIsNotNull();
    long countByAssignedUserIdIsNotNull();
}
