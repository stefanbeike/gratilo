package com.gratilo.payment.service;

import com.gratilo.payment.domain.PoolTransferEntity;
import com.gratilo.payment.domain.TransactionEntity;
import jakarta.inject.Singleton;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import java.util.UUID;
import java.util.function.Consumer;

/** A separate transactional bean ensures the scheduler cannot bypass the row lock. */
@Singleton
public class PoolSettlementLock {
    private final EntityManager entities;
    public PoolSettlementLock(EntityManager entities) { this.entities = entities; }

    @Transactional
    public void execute(UUID id, Consumer<PoolTransferEntity> operation) {
        var allocation = entities.find(PoolTransferEntity.class, id);
        if (allocation == null) return;
        // Serialize the whole source-charge budget, then reload the allocation under lock.
        // All workers take locks in this order, including workers processing different members.
        if (entities.find(TransactionEntity.class, allocation.getTransactionId(), LockModeType.PESSIMISTIC_WRITE) == null) return;
        entities.refresh(allocation, LockModeType.PESSIMISTIC_WRITE);
        operation.accept(allocation);
    }
}
