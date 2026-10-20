package com.gratilo.payment.service;

import com.gratilo.payment.domain.PoolTransferEntity;
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
        var allocation = entities.find(PoolTransferEntity.class, id, LockModeType.PESSIMISTIC_WRITE);
        if (allocation != null) operation.accept(allocation);
    }
}
