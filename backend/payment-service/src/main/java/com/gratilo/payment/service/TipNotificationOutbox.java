package com.gratilo.payment.service;

import com.gratilo.payment.api.TipNotification;
import com.gratilo.payment.domain.*;
import com.gratilo.payment.repository.PoolTransferRepository;
import io.micronaut.context.annotation.Requires;
import io.micronaut.json.JsonMapper;
import jakarta.inject.Singleton;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.util.*;

@Singleton
@Requires(property="notifications.tip-payments-enabled",value="true")
public class TipNotificationOutbox {
    private final EntityManager entities;
    private final PoolTransferRepository allocations;
    private final JsonMapper json;
    public TipNotificationOutbox(EntityManager entities,PoolTransferRepository allocations,JsonMapper json){this.entities=entities;this.allocations=allocations;this.json=json;}

    /** Joins the payment-status transaction. No SMTP or network calls occur here. */
    @Transactional
    public void enqueue(TransactionEntity tx){
        List<TipNotification> messages;
        try{messages=snapshots(tx,allocations);}catch(IllegalArgumentException|IllegalStateException invalid){
            // A malformed notification must not undo an otherwise confirmed payment.
            // No recipient is invented; this terminal diagnostic cannot be claimed for delivery.
            entities.createNativeQuery("INSERT INTO payment_mgmt.tip_notification_outbox(notification_key,transaction_id,payload,status,last_error) VALUES (:key,:tx,'{}','FAILED','INVALID_FROZEN_NOTIFICATION') ON CONFLICT (notification_key) DO NOTHING")
                .setParameter("key","tip-paid:"+tx.getId()+":invalid").setParameter("tx",tx.getId()).executeUpdate();
            org.slf4j.LoggerFactory.getLogger(getClass()).error("Tip notification snapshot unavailable transaction={} category={}",tx.getId(),invalid.getClass().getSimpleName());
            return;
        }
        for(var notification:messages)try{
            var payload=json.writeValueAsString(notification);
            entities.createNativeQuery("INSERT INTO payment_mgmt.tip_notification_outbox(notification_key,transaction_id,recipient_user_id,payload) VALUES (:key,:tx,:recipient,:payload) ON CONFLICT (notification_key) DO NOTHING")
                .setParameter("key",notification.notificationKey()).setParameter("tx",tx.getId()).setParameter("recipient",notification.recipientUserId()).setParameter("payload",payload).executeUpdate();
        }catch(java.io.IOException invalid){throw new IllegalStateException("Notification snapshot serialization failed",invalid);}
    }
    public static List<TipNotification> snapshots(TransactionEntity tx,PoolTransferRepository repository){
        if(!"STRIPE".equals(tx.getProvider())||tx.getType()!=TransactionType.TIP||tx.getStatus()!=TransactionStatus.BOOKED)return List.of();
        var pricing=TipPricingSnapshot.from(tx);boolean pool=PoolReconciliation.isPool(tx);
        var result=new ArrayList<TipNotification>();
        if(pool){
            var rows=repository.findByTransactionId(tx.getId());long total=0;var recipients=new HashSet<UUID>();
            for(var row:rows){
                if(!tx.getId().equals(row.getTransactionId())||row.getId()==null||row.getRecipientUserId()==null||!recipients.add(row.getRecipientUserId()))throw new IllegalStateException("Invalid frozen recipients");
                total=Math.addExact(total,row.getAmountMinor());
                result.add(notification(tx,pricing,row.getRecipientUserId(),row.getId(),row.getAmountMinor(),true));
            }
            if(rows.isEmpty()||total!=pricing.recipientMinor())throw new IllegalStateException("Frozen recipient total mismatch");
        }else result.add(notification(tx,pricing,tx.getAccountUserId(),null,pricing.recipientMinor(),false));
        return List.copyOf(result);
    }
    private static TipNotification notification(TransactionEntity tx,TipPricingSnapshot pricing,UUID recipient,UUID allocation,long amount,boolean pool){
        var n=new TipNotification(TipNotification.key(tx.getId(),recipient),tx.getId(),recipient,allocation,1,pricing.version(),pricing.selectedMinor(),pricing.platformFeeMinor(),pricing.estimatedFeeMinor(),Boolean.TRUE.equals(pricing.feeCovered()),pricing.recipientMinor(),amount,pool);
        n.validate();return n;
    }
}
