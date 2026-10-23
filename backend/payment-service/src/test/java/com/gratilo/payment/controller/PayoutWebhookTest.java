package com.gratilo.payment.controller;

import com.gratilo.payment.domain.*;
import com.gratilo.payment.repository.TransactionRepository;
import com.gratilo.payment.service.*;
import io.micronaut.json.JsonMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class PayoutWebhookTest {
    @Test void onlySignedConnectedAccountEventsUpdateTheirOwnPayout()throws Exception {
        var tx=new TransactionEntity();tx.setId(UUID.randomUUID());tx.setType(TransactionType.PAYOUT);tx.setAmountMinor(-1250);tx.setStatus(TransactionStatus.PENDING);tx.setProvider("STRIPE");
        PayoutState.metadata(tx,new LinkedHashMap<>(Map.of("payoutVersion",1,"stripeAccountId","acct_own","payoutStatus","pending")));
        var repo=(TransactionRepository)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{TransactionRepository.class},(p,m,a)->{throw new AssertionError("No unrelated transaction lookup: "+m.getName());});
        var processor=new StripeWebhookController(repo,"whsec_platform",true,JsonMapper.createDefault());
        int[] calls={0};processor.payouts=new PayoutStore(null){@Override public boolean webhook(String account,Map<?,?> payout,long time){calls[0]++;PayoutState.apply(tx,account,payout,time);return true;}};
        var direct=new StripeDirectWebhookController("whsec_direct",processor);
        String payload=payload(tx,"acct_own","payout.paid","paid",200,false);
        assertEquals(401,direct.receive(payload,null).code());assertEquals(0,calls[0]);
        assertEquals(401,direct.receive(payload,sign(payload,"whsec_platform")).code());assertEquals(0,calls[0]);
        String foreign=payload(tx,"acct_other","payout.paid","paid",200,false);
        assertEquals(200,direct.receive(foreign,sign(foreign,"whsec_direct")).code());assertEquals(TransactionStatus.PENDING,tx.getStatus());
        String live=payload(tx,"acct_own","payout.paid","paid",200,true);
        assertEquals(401,direct.receive(live,sign(live,"whsec_direct")).code());
        for(int n=0;n<2;n++)assertEquals(200,direct.receive(payload,sign(payload,"whsec_direct")).code());
        assertEquals(TransactionStatus.BOOKED,tx.getStatus());
        String old=payload(tx,"acct_own","payout.updated","pending",100,false);
        direct.receive(old,sign(old,"whsec_direct"));assertEquals(TransactionStatus.BOOKED,tx.getStatus());assertEquals("po_fixture",tx.getProviderReference());
    }
    @Test void settingsEventsPrepareOnlyAfterTheConnectedEndpointSignatureWasVerified()throws Exception {
        var processor=new StripeWebhookController(null,"whsec_platform",true,JsonMapper.createDefault());int[] calls={0};
        processor.payoutSetup=new StripePayoutService(null,null,null,null,"sk_test_fixture",true,false,""){
            @Override public void prepareKnownAccount(String id){assertEquals("acct_own",id);calls[0]++;}
        };
        var direct=new StripeDirectWebhookController("whsec_direct",processor);
        String event="{\"type\":\"balance_settings.updated\",\"account\":\"acct_own\",\"livemode\":false,\"data\":{\"object\":{}}}";
        assertEquals(401,direct.receive(event,null).code());assertEquals(0,calls[0]);
        assertEquals(200,processor.receive(event,sign(event,"whsec_platform")).code());assertEquals(0,calls[0]);
        for(int i=0;i<2;i++)assertEquals(200,direct.receive(event,sign(event,"whsec_direct")).code());
        assertEquals(2,calls[0]); // Preparation itself re-reads Stripe, so duplicate events do not rewrite manual schedules.
    }
    private static String payload(TransactionEntity tx,String account,String event,String state,long created,boolean live)throws Exception {
        return JsonMapper.createDefault().writeValueAsString(Map.of("type",event,"account",account,"created",created,"livemode",live,"data",Map.of("object",Map.of("id","po_fixture","amount",1250,"currency","eur","method","standard","type","bank_account","status",state,"livemode",live,"metadata",Map.of("transaction_id",tx.getId().toString())))));
    }
    private static String sign(String payload,String key)throws Exception {
        String timestamp=String.valueOf(System.currentTimeMillis()/1000);var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        return "t="+timestamp+",v1="+HexFormat.of().formatHex(mac.doFinal((timestamp+"."+payload).getBytes(StandardCharsets.UTF_8)));
    }
}
