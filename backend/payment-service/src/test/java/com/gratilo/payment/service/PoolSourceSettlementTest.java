package com.gratilo.payment.service;

import com.gratilo.payment.domain.*;
import com.gratilo.payment.repository.*;
import io.micronaut.json.JsonMapper;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** No Stripe network: the fake models source-linked transfers independently of general balance. */
class PoolSourceSettlementTest {
    @Test void fiftyFiftyTransfersPreserveTipDespiteFeeUndercoverageAndZeroBalance() {
        var f = new Fixture(1250,1250); f.chargeAmount=2563; f.actualFee=106;
        f.worker.settle(); f.worker.settle();
        assertEquals(2,f.posts.size()); assertEquals(2500,f.remote.values().stream().mapToLong(r->((Number)r.get("amount")).longValue()).sum());
        assertTrue(f.posts.stream().allMatch(p->"ch_source".equals(p.get("source_transaction"))));
        assertTrue(f.rows.stream().allMatch(r->r.getStatus().equals("TRANSFERRED")));
        assertEquals(-43,f.chargeAmount-f.actualFee-2500); // Platform deficit, never a reduced member share.
    }
    @Test void newNetSnapshotSettlesBothFrozenSharesWithoutSubtractingAnotherFeePerMember()throws Exception {
        var f=new Fixture(1198,1198);f.tx.setAmountMinor(2500);f.chargeAmount=2500;f.actualFee=114;
        var quote=TipPricing.quote(2500,false,new java.math.BigDecimal("3.15"),25);
        f.tx.setMetadata(JsonMapper.createDefault().writeValueAsString(TipPricingSnapshot.metadata(quote,false)));
        f.failSecond=true;f.worker.settle();f.worker.settle();f.worker.settle();
        assertEquals(2,f.remote.size());assertEquals(2396,f.remote.values().stream().mapToLong(r->((Number)r.get("amount")).longValue()).sum());
        assertTrue(f.posts.stream().allMatch(p->"1198".equals(p.get("amount"))));
        assertEquals(3,f.posts.size());assertEquals(2396,TipPricingSnapshot.from(f.tx).recipientMinor());
    }
    @Test void threeDifferentSharesAreFrozenAndPaidOnce() {
        var f=new Fixture(500,750,1250);f.worker.settle();f.worker.settle();
        assertEquals(List.of("500","750","1250"),f.posts.stream().map(p->p.get("amount")).toList());
        assertEquals(3,f.remote.size());
    }
    @Test void secondStripeRejectionDoesNotRepeatFirstAndNextPassRetriesSecondWithSameKey() {
        var f=new Fixture(1250,1250);f.failSecond=true;f.worker.settle();
        assertEquals("TRANSFERRED",f.rows.getFirst().getStatus());
        assertEquals("STRIPE_INSUFFICIENT_FUNDS",f.rows.get(1).getFailureCode());
        assertNull(f.rows.get(1).getStripeTransferId());
        f.worker.settle();f.worker.settle();
        assertEquals(3,f.posts.size());assertEquals(2,f.remote.size());
        assertEquals(f.keys.get(1),f.keys.get(2));assertEquals("pool-transfer-"+f.rows.get(1).getId(),f.keys.get(2));
    }
    @Test void timeoutAfterRemoteSuccessIsReconciledWithoutSecondPostEvenAfterRetentionWindow() {
        var f=new Fixture(1250,1250);f.timeoutAfterCreate=true;f.worker.settle();
        assertNull(f.rows.getFirst().getStripeTransferId());assertEquals(2,f.remote.size());
        f.rows.getFirst().setFirstAttemptAt(OffsetDateTime.now().minusDays(2));
        f.worker.settle();assertEquals(2,f.posts.size());assertEquals(2,f.remote.size());
        assertTrue(f.rows.stream().allMatch(r->r.getStripeTransferId()!=null));
    }
    @Test void uncertainAttemptWithoutRemoteMatchCannotPostAfterRetentionWindow() {
        var f=new Fixture(1250,1250);f.rows.getFirst().setFirstAttemptAt(OffsetDateTime.now().minusDays(2));
        f.worker.settle();assertEquals("RECONCILIATION_REQUIRED",f.rows.getFirst().getFailureCode());
        assertEquals(1,f.posts.size());assertEquals("1250",f.posts.getFirst().get("amount"));
        assertEquals(f.rows.get(1).getId().toString(),f.posts.getFirst().get("metadata[pool_allocation_id]"));
    }
    @Test void knownRestrictedMemberBlocksPredictablePartialDistributionWithoutRedistribution() {
        var f=new Fixture(1250,1250);f.restrictedAccount=f.rows.get(1).getStripeAccountId();f.worker.settle();
        assertTrue(f.posts.isEmpty());assertTrue(f.rows.stream().allMatch(r->"RECIPIENT_NOT_READY".equals(r.getFailureCode())));
        f.restrictedAccount=null;f.worker.settle();assertEquals(2,f.posts.size());
    }
    @Test void oversizedSnapshotBlocksAllPosts() {
        var f=new Fixture(1250,1250);f.chargeAmount=2400;f.worker.settle();assertTrue(f.posts.isEmpty());
    }
    @Test void incorrectSnapshotTotalBlocksAllPosts() {
        var f=new Fixture(1250,1250);f.rows.get(1).setAmountMinor(1200);f.worker.settle();
        assertTrue(f.posts.isEmpty());assertEquals("SOURCE_ALLOCATION_TOTAL_MISMATCH",f.rows.getFirst().getFailureCode());
    }
    @Test void unknownRemoteTransferIsNotIgnoredWhenCheckingSourceBudget() {
        var f=new Fixture(1250,1250);f.remote.put("tr_unknown",Map.of("id","tr_unknown","livemode",false,"amount",100,
                "currency","eur","source_transaction","ch_source","destination","acct_unknown","metadata",Map.of()));
        f.worker.settle();assertTrue(f.posts.isEmpty());assertEquals("AMBIGUOUS_TRANSFER",f.rows.getFirst().getFailureCode());
    }
    @Test void paginatedReconciliationRecoversSuccessWithoutReposting() {
        var f=new Fixture(1250,1250);f.worker.settle();f.paginate=true;
        f.rows.get(1).setStripeTransferId(null);f.rows.get(1).setStatus("PENDING");
        f.worker.settle();assertEquals(2,f.posts.size());assertNotNull(f.rows.get(1).getStripeTransferId());
    }
    @Test void mismatchedRemoteAmountIsNeverMistakenForAnAuthorizedReductionOfTip() {
        var f=new Fixture(1250,1250);f.worker.settle();
        f.remote.values().iterator().next().put("amount",1200);
        f.rows.get(1).setStripeTransferId(null);f.rows.get(1).setStatus("PENDING");
        f.worker.settle();assertEquals(2,f.posts.size());
        assertEquals("TRANSFER_RECONCILIATION_MISMATCH",f.rows.get(1).getFailureCode());
    }
    @Test void sourceAssociationCurrencyAndChargeStatusFailClosed() {
        for(String invalid:List.of("group","currency","unpaid","disputed","destination")) {
            var f=new Fixture(1250,1250);f.invalidSource=invalid;f.worker.settle();assertTrue(f.posts.isEmpty(),invalid);
        }
    }
    @Test void twoConcurrentWorkersReloadUnderTheSharedSourceLockAndNeverPostTwice() throws Exception {
        var f=new Fixture(1250,1250);var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var first=executor.submit(()->{await(start);f.worker.settle();});
            var second=executor.submit(()->{await(start);f.worker.settle();});
            start.countDown();first.get(10,TimeUnit.SECONDS);second.get(10,TimeUnit.SECONDS);
        }
        assertEquals(2,f.posts.size());assertEquals(2,f.remote.size());
    }
    private static void await(CountDownLatch latch){try{latch.await();}catch(InterruptedException e){throw new RuntimeException(e);}}

    private static class Fixture {
        final TransactionEntity tx=new TransactionEntity();final List<PoolTransferEntity> rows=new CopyOnWriteArrayList<>();
        final Map<String,Map<String,Object>> remote=new LinkedHashMap<>();final List<Map<String,String>> posts=new ArrayList<>();
        final List<String> keys=new ArrayList<>();final JsonMapper json=JsonMapper.createDefault();final Object sourceLock=new Object();
        final PoolTransferSettlementService worker;long chargeAmount,actualFee;boolean failSecond,timeoutAfterCreate,paginate;
        String restrictedAccount,invalidSource;
        Fixture(long...amounts) {
            tx.setId(UUID.randomUUID());tx.setAmountMinor(Arrays.stream(amounts).sum());tx.setCurrency("EUR");tx.setStatus(TransactionStatus.BOOKED);
            tx.setProvider("STRIPE");tx.setProviderReference("cs_test_source");chargeAmount=tx.getAmountMinor()+100;
            for(int i=0;i<amounts.length;i++){
                var row=new PoolTransferEntity();row.setId(UUID.randomUUID());row.setTransactionId(tx.getId());row.setRecipientUserId(UUID.randomUUID());
                row.setStripeAccountId("acct_member"+i);row.setAmountMinor(amounts[i]);row.setSettlementApproved(true);rows.add(row);
            }
            var repo=proxy(PoolTransferRepository.class,(p,m,a)->switch(m.getName()){
                case "findByStatus"->rows.stream().filter(r->r.getStatus().equals(a[0])).toList();case "findByTransactionId"->rows;case "update"->a[0];default->throw new AssertionError(m.getName());});
            var transactions=proxy(TransactionRepository.class,(p,m,a)->Optional.of(tx));
            // Simulates the DB transaction lock and fresh reload; actual lock ordering is checked separately.
            var lock=new PoolSettlementLock(null){@Override public void execute(UUID id,java.util.function.Consumer<PoolTransferEntity> operation){
                synchronized(sourceLock){operation.accept(rows.stream().filter(r->r.getId().equals(id)).findFirst().orElseThrow());}
            }};
            worker=new PoolTransferSettlementService(repo,transactions,"sk_test_fixture",true,lock){
                @Override protected String request(String path,String body,String key)throws Exception{return response(path,body,key);}
            };
        }
        String response(String path,String body,String key)throws Exception {
            if(path.startsWith("/v1/checkout/"))return json.writeValueAsString(Map.of("livemode",false,"status","complete","payment_status","paid","payment_intent","pi_source"));
            if(path.startsWith("/v1/payment_intents/")){
                var charge=new HashMap<String,Object>();charge.putAll(Map.of("id","ch_source","livemode",false,"paid",!"unpaid".equals(invalidSource),"captured",true,
                        "currency","currency".equals(invalidSource)?"usd":"eur","amount",chargeAmount,"transfer_group","group".equals(invalidSource)?"other":"gratilo_"+tx.getId(),
                        "balance_transaction",Map.of("currency","eur","status","pending","fee",actualFee)));
                if("disputed".equals(invalidSource))charge.put("disputed",true);
                if("destination".equals(invalidSource))charge.put("transfer_data",Map.of("destination","acct_wrong"));
                return json.writeValueAsString(Map.of("livemode",false,"status","succeeded","latest_charge",charge));
            }
            if(path.startsWith("/v2/core/accounts/")){
                String id=path.substring("/v2/core/accounts/".length()).split("\\?")[0];
                String account=StripeAccountFixtures.readyJson().replace("acct_fixture",id);
                return id.equals(restrictedAccount)?account.replace("active","restricted"):account;
            }
            if(path.startsWith("/v1/transfers?")){
                var all=new ArrayList<>(remote.values());
                if(paginate&&!path.contains("starting_after="))return json.writeValueAsString(Map.of("data",List.of(all.getFirst()),"has_more",true));
                return json.writeValueAsString(Map.of("data",paginate?all.subList(1,all.size()):all,"has_more",false));
            }
            assertEquals("/v1/transfers",path);assertNotNull(body); // A general /balance GET is prohibited in this flow.
            var form=new HashMap<String,String>();for(String pair:body.split("&")){var kv=pair.split("=",2);form.put(URLDecoder.decode(kv[0],StandardCharsets.UTF_8),URLDecoder.decode(kv[1],StandardCharsets.UTF_8));}
            posts.add(form);keys.add(key);
            if(failSecond&&form.get("destination").equals(rows.get(1).getStripeAccountId())) {
                failSecond=false;throw StripeCheckoutService.StripeApiException.from(400,"{\"error\":{\"code\":\"balance_insufficient\"}}","req_fixture");
            }
            String id="tr_"+remote.size();var transfer=new HashMap<String,Object>();
            transfer.putAll(Map.of("id",id,"livemode",false,"destination",form.get("destination"),"source_transaction",form.get("source_transaction"),
                    "currency","eur","amount",Long.parseLong(form.get("amount")),"amount_reversed",0,
                    "metadata",Map.of("pool_allocation_id",form.get("metadata[pool_allocation_id]"))));remote.put(id,transfer);
            if(timeoutAfterCreate){timeoutAfterCreate=false;throw new java.net.http.HttpTimeoutException("Unknown result");}
            return json.writeValueAsString(transfer);
        }
    }
    @SuppressWarnings("unchecked") private static<T>T proxy(Class<T> c,java.lang.reflect.InvocationHandler h){return(T)Proxy.newProxyInstance(c.getClassLoader(),new Class[]{c},h);}
}
