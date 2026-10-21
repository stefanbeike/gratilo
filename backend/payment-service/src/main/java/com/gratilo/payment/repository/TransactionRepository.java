package com.gratilo.payment.repository;
import com.gratilo.payment.domain.TransactionEntity;
import io.micronaut.data.annotation.Repository;
import io.micronaut.data.repository.CrudRepository;
import java.util.*;
import java.util.UUID;
@Repository
public interface TransactionRepository extends CrudRepository<TransactionEntity, UUID> {
    List<TransactionEntity> findByAccountUserIdOrderByCreatedAtDesc(UUID accountUserId);
    Optional<TransactionEntity> findByIdAndAccountUserId(UUID id, UUID accountUserId);
    Optional<TransactionEntity> findByAccountUserIdAndIdempotencyKey(UUID accountUserId, String idempotencyKey);
    Optional<TransactionEntity> findByStripePaymentIntentId(String stripePaymentIntentId);
    Optional<TransactionEntity> findByStripeChargeId(String stripeChargeId);
    Optional<TransactionEntity> findByProviderReference(String providerReference);
}
