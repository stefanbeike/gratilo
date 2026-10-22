package com.gratilo.payment.service;

import com.gratilo.payment.domain.*;
import jakarta.inject.Singleton;
import jakarta.persistence.*;
import jakarta.transaction.Transactional;
import java.time.OffsetDateTime;
import java.util.*;

/** Separate DB transactions: the intent is committed BEFORE any external Payout POST. */
@Singleton
@io.micronaut.context.annotation.Requires(beans=EntityManager.class)
public class PayoutStore {
    private final EntityManager entities;
    public PayoutStore(EntityManager entities) { this.entities=entities; }
    public record Prepared(TransactionEntity transaction, boolean created) { }
    @Transactional public TransactionEntity existing(UUID user, UUID request) {
        return entities.createQuery("from TransactionEntity where accountUserId=:user and idempotencyKey=:key",TransactionEntity.class)
                .setParameter("user",user).setParameter("key","manual-payout-"+request).getResultStream().findFirst().orElse(null);
    }
    @Transactional public List<TransactionEntity> history(UUID user) {
        return entities.createQuery("from TransactionEntity where accountUserId=:user and type=:type order by createdAt desc",TransactionEntity.class)
                .setParameter("user",user).setParameter("type",TransactionType.PAYOUT).getResultList();
    }
    @Transactional public long reserved(UUID user) {
        return history(user).stream().filter(PayoutState::managed).filter(t->"STRIPE_SANDBOX".equals(t.getProvider()))
                .filter(t->t.getStatus()!=TransactionStatus.FAILED&&t.getStatus()!=TransactionStatus.CANCELLED)
                .mapToLong(t->Math.negateExact(t.getAmountMinor())).reduce(0,Math::addExact);
    }
    @Transactional public Prepared prepare(UUID user,UUID request,Long requested,long available,String account,String bank,boolean simulation) {
        var owner=entities.createQuery("from ConnectedAccountEntity where userId=:user",ConnectedAccountEntity.class)
                .setParameter("user",user).setLockMode(LockModeType.PESSIMISTIC_WRITE).getSingleResult();
        if(!account.equals(owner.getStripeAccountId()))throw new IllegalStateException("Das Stripe-Konto wurde geändert. Bitte neu laden.");
        var existing=existing(user,request);
        if(existing!=null){StripePayoutService.sameRequest(existing,requested);return new Prepared(existing,false);}
        var history=history(user);
        if(history.stream().filter(PayoutState::managed).anyMatch(PayoutState::active))throw new IllegalStateException("Eine Auszahlung wird bereits verarbeitet. Bitte gleiche ihren Status ab.");
        if(history.stream().filter(PayoutState::managed).filter(t->t.getCreatedAt()!=null&&t.getCreatedAt().isAfter(OffsetDateTime.now().minusMinutes(1))).count()>=3)
            throw new IllegalStateException("Zu viele Auszahlungsanforderungen. Bitte warte eine Minute.");
        long balance=simulation?Math.subtractExact(available,reserved(user)):available;
        long amount=requested==null?balance:requested;StripePayoutService.validateAmount(amount,balance);
        var tx=new TransactionEntity();tx.setAccountUserId(user);tx.setAmountMinor(-amount);tx.setType(TransactionType.PAYOUT);tx.setStatus(TransactionStatus.PENDING);
        tx.setProvider(simulation?"STRIPE_SANDBOX":"STRIPE");tx.setIdempotencyKey("manual-payout-"+request);
        tx.setDescription(simulation?"Simulierte Standardauszahlung":"Stripe-Standardauszahlung");
        var m=new LinkedHashMap<String,Object>();m.put("payoutVersion",1);m.put("requestId",request.toString());m.put("stripeAccountId",account);
        m.put("destination",bank);m.put("simulation",simulation);m.put("requestedAll",requested==null);m.put("payoutStatus","submitting");m.put("gratiloPayoutFeeMinor",0);m.put("stripePayoutFeeKnown",false);
        PayoutState.metadata(tx,m);entities.persist(tx);entities.flush();return new Prepared(tx,true);
    }
    @Transactional public TransactionEntity own(UUID user,UUID id) {
        var tx=entities.find(TransactionEntity.class,id);
        if(tx==null||!user.equals(tx.getAccountUserId())||!PayoutState.managed(tx))throw new IllegalArgumentException("Auszahlung nicht gefunden.");return tx;
    }
    @Transactional public TransactionEntity result(UUID user,UUID id,String account,Map<?,?> payout,long time) {
        var tx=entities.find(TransactionEntity.class,id,LockModeType.PESSIMISTIC_WRITE);
        if(tx==null||!user.equals(tx.getAccountUserId()))throw new IllegalArgumentException("Auszahlung nicht gefunden.");
        PayoutState.apply(tx,account,payout,time);return tx;
    }
    @Transactional public TransactionEntity failed(UUID user,UUID id,boolean uncertain) {
        var tx=entities.find(TransactionEntity.class,id,LockModeType.PESSIMISTIC_WRITE);
        if(tx==null||!user.equals(tx.getAccountUserId()))throw new IllegalArgumentException("Auszahlung nicht gefunden.");
        if(tx.getProviderReference()!=null||!PayoutState.active(tx))return tx;
        tx.setStatus(uncertain?TransactionStatus.AUTHORIZED:TransactionStatus.FAILED);
        tx.setFailureCode(uncertain?"PAYOUT_OUTCOME_UNKNOWN":"PAYOUT_REJECTED");
        tx.setFailureMessage(uncertain?"Das Stripe-Ergebnis ist unklar. Bitte den Status abgleichen; keine neue Auszahlung anfordern.":"Die Auszahlung wurde nicht erstellt. Bitte prüfe Guthaben und Stripe-Einstellungen.");
        var m=PayoutState.metadata(tx);m.put("payoutStatus",uncertain?"unknown":"failed");PayoutState.metadata(tx,m);return tx;
    }
    @Transactional public boolean webhook(String account,Map<?,?> payout,long time) {
        if(account==null||!(payout.get("id") instanceof String id))return false;
        var metadata=payout.get("metadata") instanceof Map<?,?> m?m:Map.of();
        TransactionEntity tx=null;
        if(metadata.get("transaction_id") instanceof String raw)try{tx=entities.find(TransactionEntity.class,UUID.fromString(raw),LockModeType.PESSIMISTIC_WRITE);}catch(IllegalArgumentException ignored){}
        if(tx==null)tx=entities.createQuery("from TransactionEntity where providerReference=:id and type=:type",TransactionEntity.class)
                .setParameter("id",id).setParameter("type",TransactionType.PAYOUT).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultStream().findFirst().orElse(null);
        if(tx==null||!PayoutState.managed(tx))return false;
        if("STRIPE".equals(tx.getProvider()))PayoutState.apply(tx,account,payout,time);
        return true;
    }
}
