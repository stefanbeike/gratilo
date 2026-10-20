package com.gratilo.payment.repository;

import com.gratilo.payment.domain.PoolTransferEntity;
import io.micronaut.data.annotation.Repository;
import io.micronaut.data.repository.CrudRepository;
import java.util.List;
import java.util.UUID;

@Repository
public interface PoolTransferRepository extends CrudRepository<PoolTransferEntity, UUID> {
    List<PoolTransferEntity> findByStatus(String status);
    List<PoolTransferEntity> findByTransactionId(UUID transactionId);
    List<PoolTransferEntity> findByRecipientUserIdOrderByCreatedAtDesc(UUID recipientUserId);
}
