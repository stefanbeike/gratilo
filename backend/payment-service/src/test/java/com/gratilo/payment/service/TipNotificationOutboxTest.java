package com.gratilo.payment.service;
import com.gratilo.payment.domain.*;
import com.gratilo.payment.repository.PoolTransferRepository;
import io.micronaut.json.JsonMapper;
import java.util.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class TipNotificationOutboxTest {
    @Test void notificationSnapshotErrorsAreQuarantinedWithoutUndoingThePayment()throws Exception{
        var tx=tx(false,true);tx.setMetadata("{\"pricingVersion\":7}");var statements=new ArrayList<String>();
        var query=(jakarta.persistence.Query)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{jakarta.persistence.Query.class},(p,m,a)->switch(m.getName()){case "setParameter"->p;case "executeUpdate"->1;default->throw new AssertionError(m.getName());});
        var em=(jakarta.persistence.EntityManager)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{jakarta.persistence.EntityManager.class},(p,m,a)->{assertEquals("createNativeQuery",m.getName());statements.add((String)a[0]);return query;});
        new TipNotificationOutbox(em,null,JsonMapper.createDefault()).enqueue(tx);
        assertEquals(TransactionStatus.BOOKED,tx.getStatus());assertEquals(1,statements.size());assertTrue(statements.getFirst().contains("'FAILED'"));assertFalse(statements.getFirst().contains("recipient_user_id"));
    }
    TransactionEntity tx(boolean pool,boolean covered)throws Exception{
        var tx=new TransactionEntity();tx.setId(UUID.randomUUID());tx.setAccountUserId(UUID.randomUUID());tx.setType(TransactionType.TIP);tx.setProvider("STRIPE");tx.setStatus(TransactionStatus.BOOKED);tx.setAmountMinor(10000);
        var q=pool?TipPricing.poolQuote(10000,covered,new BigDecimal("3.15"),25,TipPricing.DEFAULT_PLATFORM_PERCENT):TipPricing.quote(10000,covered,new BigDecimal("3.15"),25);
        var meta=TipPricingSnapshot.metadata(q,covered);if(pool)meta.put("poolId",UUID.randomUUID().toString());tx.setMetadata(JsonMapper.createDefault().writeValueAsString(meta));return tx;
    }
    @Test void individualUsesFrozenNetBothFeeChoices()throws Exception{
        for(boolean covered:new boolean[]{true,false}){
            var t=tx(false,covered);var n=TipNotificationOutbox.snapshots(t,null).getFirst();
            assertEquals(covered?9500:9160,n.personalAmountMinor());assertEquals(500,n.platformFeeMinor());assertEquals(t.getAccountUserId(),n.recipientUserId());assertFalse(n.pool());
            assertEquals(n,TipNotificationOutbox.snapshots(t,null).getFirst());
        }
    }
    @Test void poolOnlyNotifiesFrozenRecipientsEvenAfterMembershipChanges()throws Exception{
        for(boolean covered:new boolean[]{true,false}){
            var t=tx(true,covered);long total=TipPricingSnapshot.from(t).recipientMinor();var rows=new ArrayList<PoolTransferEntity>();
            for(int i=0;i<3;i++){var r=new PoolTransferEntity();r.setId(UUID.randomUUID());r.setTransactionId(t.getId());r.setRecipientUserId(UUID.randomUUID());r.setAmountMinor(i<2?total/3:total-2*(total/3));rows.add(r);}
            var repo=StripeFeeAuditTest.proxy(PoolTransferRepository.class,(p,m,a)->{assertEquals("findByTransactionId",m.getName());return rows;});
            var notifications=TipNotificationOutbox.snapshots(t,repo);assertEquals(3,notifications.size());
            assertEquals(total,notifications.stream().mapToLong(n->n.personalAmountMinor()).sum());
            assertTrue(notifications.stream().allMatch(n->n.platformFeeMinor()==500&&n.recipientTotalMinor()==total));
            assertEquals(3,notifications.stream().map(n->n.notificationKey()).distinct().count());
            rows.get(0).setRecipientUserId(rows.get(1).getRecipientUserId());assertThrows(IllegalStateException.class,()->TipNotificationOutbox.snapshots(t,repo));
        }
    }
    @Test void failedPendingCancelledRefundAndNonStripeNeverQueue()throws Exception{
        var t=tx(false,true);for(var state:List.of(TransactionStatus.PENDING,TransactionStatus.FAILED,TransactionStatus.CANCELLED,TransactionStatus.REFUNDED)){
            t.setStatus(state);assertTrue(TipNotificationOutbox.snapshots(t,null).isEmpty());
        }
        t.setStatus(TransactionStatus.BOOKED);t.setProvider("DEMO");assertTrue(TipNotificationOutbox.snapshots(t,null).isEmpty());
    }
    @Test void historicalFixedCommissionIsReadWithoutRepricing()throws Exception{
        var t=tx(true,true);var old=new com.gratilo.payment.api.TipQuote(10000,351,10351,"EUR",new BigDecimal("3.15"),25,9955,true,351,"Historical",5,BigDecimal.ZERO,45);
        var meta=TipPricingSnapshot.metadata(old,true);meta.put("poolId",UUID.randomUUID().toString());t.setMetadata(JsonMapper.createDefault().writeValueAsString(meta));
        var r=new PoolTransferEntity();r.setId(UUID.randomUUID());r.setTransactionId(t.getId());r.setRecipientUserId(UUID.randomUUID());r.setAmountMinor(9955);
        var repo=StripeFeeAuditTest.proxy(PoolTransferRepository.class,(p,m,a)->List.of(r));var before=t.getMetadata();var n=TipNotificationOutbox.snapshots(t,repo).getFirst();
        assertEquals(45,n.platformFeeMinor());assertEquals(9955,n.personalAmountMinor());assertEquals(before,t.getMetadata());
    }
}
