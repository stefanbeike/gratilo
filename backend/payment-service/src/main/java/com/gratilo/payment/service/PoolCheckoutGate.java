package com.gratilo.payment.service;

import jakarta.inject.Singleton;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.util.UUID;

/** Durable admission across services. Unconfirmed remote outcomes never expire automatically. */
@Singleton
public class PoolCheckoutGate {
    private final EntityManager entities;
    public PoolCheckoutGate(EntityManager entities){this.entities=entities;}
    private boolean lock(UUID poolId){
        entities.createNativeQuery("INSERT INTO payment_mgmt.pool_checkout_gates(pool_id) VALUES (:pool) ON CONFLICT DO NOTHING").setParameter("pool",poolId).executeUpdate();
        return Boolean.TRUE.equals(entities.createNativeQuery("SELECT closed FROM payment_mgmt.pool_checkout_gates WHERE pool_id=:pool FOR UPDATE").setParameter("pool",poolId).getSingleResult());
    }
    @Transactional public UUID admit(UUID poolId){
        if(lock(poolId))throw new PoolClosedException();
        UUID id=UUID.randomUUID();
        entities.createNativeQuery("INSERT INTO payment_mgmt.pool_checkout_reservations(id,pool_id) VALUES (:id,:pool)").setParameter("id",id).setParameter("pool",poolId).executeUpdate();
        return id;
    }
    @Transactional public void finish(UUID reservation){
        entities.createNativeQuery("UPDATE payment_mgmt.pool_checkout_reservations SET finished_at=now() WHERE id=:id AND finished_at IS NULL").setParameter("id",reservation).executeUpdate();
    }
    @Transactional public boolean freeze(UUID poolId){
        lock(poolId);
        entities.createNativeQuery("UPDATE payment_mgmt.pool_checkout_gates SET closed=TRUE,closed_at=COALESCE(closed_at,now()) WHERE pool_id=:pool").setParameter("pool",poolId).executeUpdate();
        long inflight=((Number)entities.createNativeQuery("SELECT count(*) FROM payment_mgmt.pool_checkout_reservations WHERE pool_id=:pool AND finished_at IS NULL").setParameter("pool",poolId).getSingleResult()).longValue();
        // Financial rows are immutable history: never cancel, delete or redistribute them here.
        // FAILED/CANCELLED are safe only without submitted/ambiguous transfers. Disputes always block.
        long obligations=((Number)entities.createNativeQuery("""
            SELECT count(*) FROM payment_mgmt.transactions t
            WHERE t.metadata->>'poolId' = :pool AND (
                t.status NOT IN ('COMPLETED','BOOKED','FAILED','CANCELLED','REFUNDED','CHARGEBACK')
                OR (t.type='TIP' AND t.status IN ('BOOKED','COMPLETED','REFUNDED','CHARGEBACK')
                    AND NOT EXISTS (SELECT 1 FROM payment_mgmt.pool_transfers saved WHERE saved.transaction_id=t.id))
                OR EXISTS (SELECT 1 FROM payment_mgmt.pool_transfers a WHERE a.transaction_id=t.id AND (
                    a.status NOT IN ('TRANSFERRED','REVERSED') AND NOT (
                        t.status IN ('FAILED','CANCELLED') AND a.stripe_transfer_id IS NULL AND a.first_attempt_at IS NULL)
                    OR a.reversal_amount_minor > a.reversed_amount_minor OR (t.status IN ('REFUNDED','CHARGEBACK') AND a.reversed_amount_minor < a.amount_minor)))
            )
            """).setParameter("pool",poolId.toString()).getSingleResult()).longValue();
        return inflight==0 && obligations==0;
    }
    public static class PoolClosedException extends IllegalStateException {
        public PoolClosedException(){super("Dieser Pool wird aufgelöst und nimmt keine neuen Zahlungen mehr an.");}
    }
}
