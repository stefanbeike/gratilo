package com.gratilo.payment.service;

import com.gratilo.payment.domain.*;
import com.gratilo.payment.repository.*;
import com.gratilo.payment.controller.StripeWebhookController;
import io.micronaut.json.JsonMapper;
import java.lang.reflect.Proxy;
import java.time.OffsetDateTime;
import java.util.*;
import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MultiMemberSettlementTest {
    @SuppressWarnings("unchecked") static <T>T repo(Class<T> type,java.lang.reflect.InvocationHandler handler){return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class[]{type},handler);}
    @Test void twoMembersReceiveTheirFrozenSharesOnlyOnceAndRepeatedRefundsReverseOnlyOnce()throws Exception {
        var mapper=JsonMapper.createDefault();
        var tx=new TransactionEntity();tx.setId(UUID.randomUUID());tx.setStatus(TransactionStatus.BOOKED);tx.setProvider("STRIPE");tx.setProviderReference("cs_test_fixture");tx.setType(TransactionType.TIP);
        var rows=new ArrayList<PoolTransferEntity>();
        for(int i=1;i<=2;i++){
            var row=new PoolTransferEntity();row.setId(UUID.randomUUID());row.setTransactionId(tx.getId());row.setRecipientUserId(new UUID(0,i));row.setStripeAccountId("acct_member"+i);row.setSharePercent(new java.math.BigDecimal("50"));row.setAmountMinor(500);row.setStatus("PENDING");row.setSettlementApproved(true);row.setCreatedAt(OffsetDateTime.now());row.setUpdatedAt(OffsetDateTime.now());rows.add(row);
        }
        var txs=repo(TransactionRepository.class,(p,m,a)->switch(m.getName()){case "findById"->Optional.of(tx);case "update"->a[0];default->throw new AssertionError(m.getName());});
        var transfers=repo(PoolTransferRepository.class,(p,m,a)->switch(m.getName()){
            case "findByStatus"->rows.stream().filter(r->r.getStatus().equals(a[0])).toList();case "findByTransactionId"->rows;case "update"->a[0];default->throw new AssertionError(m.getName());
        });
        var remote=new LinkedHashMap<String,Map<String,Object>>();var posts=new ArrayList<String>();var funds=new long[]{1000};
        var worker=new PoolTransferSettlementService(transfers,txs,"sk_test_fixture",true){
            @Override protected String request(String path,String body,String key)throws Exception {
                if(path.startsWith("/v1/checkout/"))return "{\"livemode\":false,\"status\":\"complete\",\"payment_status\":\"paid\",\"payment_intent\":\"pi_fixture\"}";
                if(path.startsWith("/v1/payment_intents/"))return "{\"livemode\":false,\"status\":\"succeeded\",\"latest_charge\":{\"id\":\"ch_fixture\",\"livemode\":false,\"paid\":true,\"captured\":true,\"currency\":\"eur\",\"balance_transaction\":{\"currency\":\"eur\",\"status\":\"available\"}}}";
                if(path.equals("/v1/balance"))return "{\"livemode\":false,\"available\":[{\"currency\":\"eur\",\"amount\":"+funds[0]+"}]}";
                if(path.startsWith("/v2/core/accounts/")){String id=path.substring("/v2/core/accounts/".length()).split("\\?")[0];return StripeAccountFixtures.readyJson().replace("acct_fixture",id);}
                if(path.startsWith("/v1/transfers?"))return mapper.writeValueAsString(Map.of("data",remote.values(),"has_more",false));
                if(body==null&&path.startsWith("/v1/transfers/"))return mapper.writeValueAsString(remote.get(path.substring("/v1/transfers/".length())));
                posts.add(path+" "+key);var form=new HashMap<String,String>();for(String part:body.split("&")){String[] kv=part.split("=",2);form.put(java.net.URLDecoder.decode(kv[0],StandardCharsets.UTF_8),java.net.URLDecoder.decode(kv[1],StandardCharsets.UTF_8));}
                long amount=Long.parseLong(form.get("amount"));
                if(path.equals("/v1/transfers")){
                    String id="tr_member"+(remote.size()+1);var transfer=new HashMap<String,Object>();transfer.put("id",id);transfer.put("livemode",false);transfer.put("destination",form.get("destination"));transfer.put("source_transaction",form.get("source_transaction"));transfer.put("amount",amount);transfer.put("currency","eur");transfer.put("amount_reversed",0L);transfer.put("metadata",Map.of("pool_allocation_id",form.get("metadata[pool_allocation_id]")));remote.put(id,transfer);funds[0]-=amount;return mapper.writeValueAsString(transfer);
                }
                String id=path.split("/")[3];var transfer=remote.get(id);transfer.put("amount_reversed",((Number)transfer.get("amount_reversed")).longValue()+amount);
                return mapper.writeValueAsString(Map.of("id","trr_"+id,"transfer",id,"amount",amount));
            }
        };
        worker.settle();worker.settle();
        assertEquals(2,remote.size());assertEquals(1000,remote.values().stream().mapToLong(r->((Number)r.get("amount")).longValue()).sum());
        assertEquals(Set.of("acct_member1","acct_member2"),remote.values().stream().map(r->r.get("destination")).collect(java.util.stream.Collectors.toSet()));
        assertEquals(2,posts.size());
        var webhook=new StripeWebhookController(txs,"whsec_unit_fixture",true,mapper,transfers);
        String payload=mapper.writeValueAsString(Map.of("livemode",false,"type","charge.refunded","data",Map.of("object",Map.of("id","ch_fixture","amount",1041,"amount_refunded",520,"refunded",false,"metadata",Map.of("transaction_id",tx.getId().toString())))));
        long t=System.currentTimeMillis()/1000;var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec("whsec_unit_fixture".getBytes(StandardCharsets.UTF_8),"HmacSHA256"));String sig="t="+t+",v1="+HexFormat.of().formatHex(mac.doFinal((t+"."+payload).getBytes(StandardCharsets.UTF_8)));
        webhook.receive(payload,sig);webhook.receive(payload,sig);
        assertEquals(499,rows.stream().mapToLong(PoolTransferEntity::getReversalAmountMinor).sum());
        worker.settle();worker.settle();
        assertEquals(4,posts.size());assertEquals(499,rows.stream().mapToLong(PoolTransferEntity::getReversedAmountMinor).sum());
        // A lost local reversal update is reconciled against Stripe, without a second reversal POST.
        rows.getFirst().setReversedAmountMinor(0);worker.settle();assertEquals(4,posts.size());
    }
}
