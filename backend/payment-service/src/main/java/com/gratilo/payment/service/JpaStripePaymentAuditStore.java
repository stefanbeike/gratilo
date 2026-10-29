package com.gratilo.payment.service;

import com.gratilo.payment.domain.StripePaymentAuditEntity;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import jakarta.persistence.*;
import jakarta.transaction.Transactional;
import java.util.*;
import java.util.function.Consumer;

@Singleton @Requires(beans=EntityManager.class)
public class JpaStripePaymentAuditStore implements StripePaymentAuditStore {
    private final EntityManager em;
    public JpaStripePaymentAuditStore(EntityManager em){this.em=em;}
    @Override @Transactional public void enroll(StripePaymentAuditEntity a){
        em.flush();
        int created=em.createNativeQuery("INSERT INTO payment_mgmt.stripe_payment_audit(payment_id,payment_type,livemode,charge_account_id,currency,expected_gross_minor,recipient_target_minor) VALUES (:id,:kind,:live,:account,:currency,:gross,:recipient) ON CONFLICT DO NOTHING")
            .setParameter("id",a.getPaymentId()).setParameter("kind",a.getPaymentType()).setParameter("live",a.isLivemode()).setParameter("account",a.getChargeAccountId()).setParameter("currency",a.getCurrency()).setParameter("gross",a.getExpectedGrossMinor()).setParameter("recipient",a.getRecipientTargetMinor()).executeUpdate();
        if(created==1)event(a.getPaymentId(),"enrolled","ENROLLED","{}");
    }
    @Override @Transactional public Optional<StripePaymentAuditEntity> find(UUID id){return Optional.ofNullable(em.find(StripePaymentAuditEntity.class,id));}
    @Override @Transactional public List<StripePaymentAuditEntity> search(String ref,boolean live){
        @SuppressWarnings("unchecked") List<StripePaymentAuditEntity> result=em.createNativeQuery("SELECT a.* FROM payment_mgmt.stripe_payment_audit a WHERE a.livemode=:live AND (CAST(a.payment_id AS text)=:ref OR a.payment_intent_id=:ref OR a.charge_id=:ref OR a.checkout_session_id=:ref OR EXISTS (SELECT 1 FROM payment_mgmt.stripe_payment_transfer_audit t WHERE t.payment_id=a.payment_id AND t.livemode=a.livemode AND t.transfer_id=:ref)) ORDER BY a.created_at,a.payment_id",StripePaymentAuditEntity.class).setParameter("live",live).setParameter("ref",ref).getResultList();return result;
    }
    @Override @Transactional public List<UUID> due(boolean live,int limit){return em.createQuery("select a.paymentId from StripePaymentAuditEntity a where a.livemode=:live and a.nextCheckAt<=CURRENT_TIMESTAMP order by a.nextCheckAt,a.paymentId",UUID.class).setParameter("live",live).setMaxResults(limit).getResultList();}
    @Override @Transactional public void locked(UUID id,Consumer<StripePaymentAuditEntity> work){
        // Existing webhook/settlement writes must acquire their transaction/allocation locks first.
        // Otherwise a webhook could hold AUDIT while a settlement holds TX and waits for AUDIT.
        em.flush();
        var row=em.find(StripePaymentAuditEntity.class,id,LockModeType.PESSIMISTIC_WRITE);
        if(row!=null){em.refresh(row,LockModeType.PESSIMISTIC_WRITE);work.accept(row);}
    }
    @Override public void snapshot(StripePaymentAuditEntity a,List<Map<String,Object>> transfers,List<Map<String,Object>> fees){
        for(var t:transfers)em.createNativeQuery("INSERT INTO payment_mgmt.stripe_payment_transfer_audit(payment_id,transfer_id,livemode,destination_account_id,source_charge_id,amount_minor,reversed_minor,currency,balance_transaction_id,destination_payment_id,recipient_balance_transaction_id,financial_data,observed_revision) VALUES (:p,:id,:live,:dest,:source,:amount,:reversed,:currency,:balance,:payment,:recipientBalance,:data,:revision) ON CONFLICT(payment_id,transfer_id) DO UPDATE SET reversed_minor=EXCLUDED.reversed_minor,balance_transaction_id=EXCLUDED.balance_transaction_id,destination_payment_id=EXCLUDED.destination_payment_id,recipient_balance_transaction_id=EXCLUDED.recipient_balance_transaction_id,financial_data=EXCLUDED.financial_data,observed_revision=EXCLUDED.observed_revision")
            .setParameter("p",a.getPaymentId()).setParameter("id",t.get("id")).setParameter("live",a.isLivemode()).setParameter("dest",t.get("destination")).setParameter("source",t.get("source_transaction")).setParameter("amount",t.get("amount")).setParameter("reversed",t.get("amount_reversed")).setParameter("currency",t.get("currency")).setParameter("balance",t.get("balance_transaction")).setParameter("payment",t.get("destination_payment")).setParameter("recipientBalance",t.get("recipient_balance_transaction")).setParameter("data",StripePaymentAuditJson.write(t)).setParameter("revision",a.getRevision()).executeUpdate();
        if("COMPLETE".equals(a.getStatus()))em.createNativeQuery("DELETE FROM payment_mgmt.stripe_payment_fee_audit WHERE payment_id=:p").setParameter("p",a.getPaymentId()).executeUpdate();
        for(var f:fees)em.createNativeQuery("INSERT INTO payment_mgmt.stripe_payment_fee_audit(payment_id,cost_key,livemode,account_id,source_id,fee_type,amount_minor,currency,financial_data,observed_revision) VALUES (:p,:key,:live,:account,:source,:type,:amount,:currency,:data,:revision) ON CONFLICT(payment_id,cost_key) DO UPDATE SET amount_minor=EXCLUDED.amount_minor,financial_data=EXCLUDED.financial_data,observed_revision=EXCLUDED.observed_revision")
            .setParameter("p",a.getPaymentId()).setParameter("key",f.get("key")).setParameter("live",a.isLivemode()).setParameter("account",f.get("account")).setParameter("source",f.get("source")).setParameter("type",f.get("type")).setParameter("amount",f.get("amount")).setParameter("currency",f.get("currency")).setParameter("data",StripePaymentAuditJson.write(f)).setParameter("revision",a.getRevision()).executeUpdate();
        em.merge(a);
    }
    @Override public boolean event(UUID id,String key,String type,String data){return em.createNativeQuery("INSERT INTO payment_mgmt.stripe_payment_audit_event(payment_id,event_key,event_type,financial_data) VALUES (:id,:key,:type,:data) ON CONFLICT(payment_id,event_key) DO NOTHING").setParameter("id",id).setParameter("key",key).setParameter("type",type).setParameter("data",data).executeUpdate()==1;}
    @Override @Transactional public List<Map<String,Object>> events(UUID id,long after,int limit){
        @SuppressWarnings("unchecked") List<Object[]> rows=em.createNativeQuery("SELECT id,event_key,event_type,financial_data,recorded_at FROM payment_mgmt.stripe_payment_audit_event WHERE payment_id=:p AND id>:after ORDER BY id LIMIT :limit").setParameter("p",id).setParameter("after",after).setParameter("limit",limit).getResultList();
        var result=new ArrayList<Map<String,Object>>();for(var r:rows)result.add(Map.of("id",((Number)r[0]).longValue(),"key",r[1],"type",r[2],"data",StripePaymentAuditJson.map(r[3].toString()),"recordedAt",r[4].toString()));return result;
    }
}
