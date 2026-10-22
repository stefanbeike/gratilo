package com.gratilo.payment.controller;

import com.gratilo.payment.service.*;
import com.gratilo.payment.domain.*;
import com.gratilo.payment.repository.TransactionRepository;
import io.micronaut.json.JsonMapper;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DirectWebhookTest {
    private final TransactionEntity tx=new TransactionEntity();
    private int updates;
    private StripeWebhookController processor()throws Exception {return processor(false);}
    private StripeWebhookController processor(boolean stripeCollector)throws Exception {
        tx.setId(UUID.randomUUID());tx.setType(TransactionType.TIP);tx.setProvider("STRIPE");tx.setAmountMinor(2500);tx.setStatus(TransactionStatus.PENDING);
        var meta=TipPricingSnapshot.metadata(stripeCollector?TipPricing.directStripeQuote(2500,true,new BigDecimal("3.15"),25,TipPricing.DEFAULT_PLATFORM_PERCENT):TipPricing.quote(2500,true,new BigDecimal("3.15"),25),true);
        meta.put("chargeModel","DIRECT");meta.put("stripeAccountId","acct_frozen");meta.put("feesCollector",stripeCollector?"stripe":"application");
        tx.setMetadata(JsonMapper.createDefault().writeValueAsString(meta));
        var repo=(TransactionRepository)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{TransactionRepository.class},(p,m,a)->switch(m.getName()){
            case "findById"->Optional.of(tx);case "update"->{updates++;yield a[0];}default->Optional.empty();});
        return new StripeWebhookController(repo,"whsec_platform",true,JsonMapper.createDefault());
    }
    private String event(String type,String account,String status)throws Exception {
        var object=new HashMap<String,Object>();object.put("id",type.startsWith("charge.dispute")?"dp_fixture":"pi_fixture");object.put("metadata",Map.of("transaction_id",tx.getId().toString()));
        if(status!=null)object.put("status",status);
        var body=new HashMap<String,Object>();body.put("id","evt_fixture");body.put("type",type);body.put("livemode",false);body.put("data",Map.of("object",object));if(account!=null)body.put("account",account);
        return JsonMapper.createDefault().writeValueAsString(body);
    }
    @Test void onlyMatchingConnectedScopeMayBookDirectChargeAndDuplicatesAreHarmless()throws Exception {
        for(boolean collector:new boolean[]{false,true}) new DirectWebhookTest().onlyMatchingConnectedScopeMayBookDirectChargeAndDuplicatesAreHarmlessForCollector(collector);
    }
    private void onlyMatchingConnectedScopeMayBookDirectChargeAndDuplicatesAreHarmlessForCollector(boolean stripeCollector)throws Exception {
        var p=processor(stripeCollector);p.processVerified(event("payment_intent.succeeded","acct_other",null),true);assertEquals(0,updates);
        p.processVerified(event("payment_intent.succeeded",null,null),false);assertEquals(0,updates);
        p.processVerified(event("payment_intent.succeeded","acct_frozen",null),false);assertEquals(0,updates);
        p.processVerified(event("payment_intent.succeeded","acct_frozen",null),true);assertEquals(TransactionStatus.BOOKED,tx.getStatus());assertEquals(1,updates);
        p.processVerified(event("payment_intent.succeeded","acct_frozen",null),true);assertEquals(1,updates);
        p.processVerified(event("payment_intent.payment_failed","acct_frozen",null),true);assertEquals(TransactionStatus.BOOKED,tx.getStatus());
    }
    @Test void signedDirectEndpointDoesNotAcceptInvalidSignaturesOrLiveEvents()throws Exception {
        var p=processor();var endpoint=new StripeDirectWebhookController("whsec_direct",p);
        assertEquals(401,endpoint.receive(event("payment_intent.succeeded","acct_frozen",null),"t=1,v1=bad").code());assertEquals(0,updates);
        p.processVerified(event("payment_intent.succeeded","acct_frozen",null).replace("\"livemode\":false","\"livemode\":true"),true);assertEquals(0,updates);
    }
    @Test void disputeStatesWorkInFrozenScopeAndLaterSuccessDoesNotRestoreLostDispute()throws Exception {
        for(boolean collector:new boolean[]{false,true}) new DirectWebhookTest().disputeStatesWorkInFrozenScopeAndLaterSuccessDoesNotRestoreLostDisputeForCollector(collector);
    }
    private void disputeStatesWorkInFrozenScopeAndLaterSuccessDoesNotRestoreLostDisputeForCollector(boolean stripeCollector)throws Exception {
        var p=processor(stripeCollector);p.processVerified(event("payment_intent.succeeded","acct_frozen",null),true);
        p.processVerified(event("charge.dispute.created","acct_frozen",null),true);assertEquals(TransactionStatus.DISPUTED,tx.getStatus());
        p.processVerified(event("charge.dispute.closed","acct_frozen","lost"),true);assertEquals(TransactionStatus.CHARGEBACK,tx.getStatus());
        p.processVerified(event("payment_intent.succeeded","acct_frozen",null),true);assertEquals(TransactionStatus.CHARGEBACK,tx.getStatus());
    }
    @Test void fullAndPartialRefundSnapshotsDoNotRecalculateFrozenPricing()throws Exception {
        for(boolean collector:new boolean[]{false,true}) new DirectWebhookTest().fullAndPartialRefundSnapshotsDoNotRecalculateFrozenPricingForCollector(collector);
    }
    private void fullAndPartialRefundSnapshotsDoNotRecalculateFrozenPricingForCollector(boolean stripeCollector)throws Exception {
        var p=processor(stripeCollector);p.processVerified(event("payment_intent.succeeded","acct_frozen",null),true);
        String frozen=tx.getMetadata();
        var object=new HashMap<String,Object>();object.put("id","ch_fixture");object.put("amount",2607);object.put("amount_refunded",1000);
        object.put("metadata",Map.of("transaction_id",tx.getId().toString()));
        var body=Map.of("livemode",false,"type","charge.refunded","account","acct_frozen","data",Map.of("object",object));
        p.processVerified(JsonMapper.createDefault().writeValueAsString(body),true);assertEquals(TransactionStatus.BOOKED,tx.getStatus());
        object.put("amount_refunded",2607);p.processVerified(JsonMapper.createDefault().writeValueAsString(body),true);
        assertEquals(TransactionStatus.REFUNDED,tx.getStatus());assertEquals(frozen,tx.getMetadata());
        p.processVerified(event("payment_intent.succeeded","acct_frozen",null),true);assertEquals(TransactionStatus.REFUNDED,tx.getStatus());
    }

    @Test void historicalDestinationChargeRemainsPlatformScoped()throws Exception {
        var p=processor();tx.setMetadata("{\"totalAmountMinor\":2500,\"feeCovered\":false}");
        p.processVerified(event("payment_intent.succeeded","acct_frozen",null),true);assertEquals(0,updates);
        p.processVerified(event("payment_intent.succeeded",null,null),false);assertEquals(TransactionStatus.BOOKED,tx.getStatus());
    }
}
