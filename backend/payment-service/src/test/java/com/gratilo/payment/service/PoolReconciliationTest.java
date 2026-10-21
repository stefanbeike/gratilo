package com.gratilo.payment.service;

import com.gratilo.payment.domain.*;
import com.gratilo.payment.repository.*;
import io.micronaut.json.JsonMapper;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PoolReconciliationTest {
    @Test void hundredEuroPoolRetainsExactlyFiveEurosAfterBothTransfers()throws Exception {
        for(boolean covered:new boolean[]{true,false}){
            var f=new Fixture(covered);f.settle(0);f.settle(1);var r=f.read();
            assertEquals("RECONCILED",r.status());assertEquals(500,r.agreedCommissionMinor());
            assertEquals(covered?9500:9160,r.intendedRecipientsMinor());
            assertEquals(covered?351:340,r.actualProcessingFeeMinor());assertEquals(500,r.platformResidualMinor());
            assertEquals(500,r.economicNetRevenueMinor());assertEquals(0,r.outstandingRecipientsMinor());
            assertEquals(3,r.entries().size());assertEquals(covered?List.of(4750L,4750L):List.of(4580L,4580L),f.rows.stream().map(PoolTransferEntity::getAmountMinor).toList());
        }
    }
    @Test void delayedSecondTransferIsALiabilityRatherThanEarnedPlatformIncome()throws Exception {
        var f=new Fixture(true);f.settle(0);var r=f.read();
        assertEquals("TRANSFERS_PENDING",r.status());assertEquals(5250,r.platformResidualMinor());
        assertEquals(4750,r.outstandingRecipientsMinor());assertNull(r.economicNetRevenueMinor());
        f.settle(1);assertEquals(500,f.read().economicNetRevenueMinor());
        assertEquals(2,f.remoteTransfers.size());assertEquals(f.read().platformResidualMinor(),f.read().platformResidualMinor());
    }
    @Test void knownStripeSuccessAfterTimeoutStillNeedsDurableLocalTransferIdentity()throws Exception {
        var f=new Fixture(true);f.settle(0);f.settle(1);f.rows.get(1).setStripeTransferId(null);f.rows.get(1).setStatus("PENDING");
        var r=f.read();assertEquals("LOCAL_SETTLEMENT_PENDING",r.status());assertEquals(0,r.outstandingRecipientsMinor());
        assertEquals(500,r.platformResidualMinor());assertNull(r.economicNetRevenueMinor());
        assertNull(f.rows.get(1).getStripeTransferId()); // this GET-only report does not mutate settlement
    }
    @Test void actualCostDifferencesNeverChangeCommissionOrFrozenRecipients()throws Exception {
        for(boolean covered:new boolean[]{true,false})for(long difference:new long[]{-20,20}){
            var f=new Fixture(covered);long frozen=f.rows.stream().mapToLong(PoolTransferEntity::getAmountMinor).sum();
            f.audit.setActualFeeMinor(f.audit.getActualFeeMinor()+difference);f.settle(0);f.settle(1);
            var r=f.read();assertEquals("RECONCILED",r.status());assertEquals(500-difference,r.economicNetRevenueMinor());
            assertEquals(500,r.agreedCommissionMinor());assertEquals(frozen,r.transferredMinor());
        }
    }
    @Test void fullRefundAndReversalsKeepUnreturnedStripeCostsAndUnknownCommissionPolicyVisible()throws Exception {
        var f=new Fixture(true);f.settle(0);f.settle(1);f.refund(f.pricing.totalAmountMinor());
        var pending=f.read();assertEquals("REVERSALS_PENDING",pending.status());assertEquals(9500,pending.pendingReversalsMinor());
        assertNull(pending.economicNetRevenueMinor());
        f.reverse(0,f.rows.get(0).getReversalAmountMinor());f.reverse(1,f.rows.get(1).getReversalAmountMinor());
        var r=f.read();assertEquals("REFUND_REVIEW_REQUIRED",r.status());assertEquals(-351,r.platformResidualMinor());
        assertEquals(9500,r.reversedMinor());assertEquals(10351,r.refundedMinor());assertEquals(0,r.pendingReversalsMinor());
        assertNull(r.economicNetRevenueMinor());
    }
    @Test void partialRefundUsesFrozenAmountsAndDoesNotInventACommissionRefundDecision()throws Exception {
        var f=new Fixture(true);f.settle(0);f.settle(1);f.refund(5000);
        long expected=9500L*5000/10351;
        assertEquals(expected,f.read().pendingReversalsMinor());
        for(int i=0;i<2;i++)f.reverse(i,f.rows.get(i).getReversalAmountMinor());
        var r=f.read();assertEquals("REFUND_REVIEW_REQUIRED",r.status());
        assertEquals(500-5000+expected,r.platformResidualMinor());assertNull(r.economicNetRevenueMinor());
    }
    @Test void pendingAndFailedRefundLedgerEntriesNeverInventACompleteResidual()throws Exception {
        var f=new Fixture(true);f.settle(0);f.settle(1);
        var refund=new LinkedHashMap<String,Object>(Map.of("id","re_pending","charge","ch_fixture","currency","eur","amount",100,"status","pending","balance_transaction","txn_pending_refund"));
        f.refunds.add(refund);
        assertEquals("REFUND_REVIEW_REQUIRED",f.read().status());assertNull(f.read().platformResidualMinor());assertNull(f.read().economicNetRevenueMinor());
        refund.put("status","failed");
        assertEquals("FAILED_REFUND_LEDGER_REVIEW_REQUIRED",f.read().reason());assertNull(f.read().platformResidualMinor());
    }
    @Test void oldFortyFiveCentPoolSnapshotIsNotRepricedByTheReport()throws Exception {
        var f=new Fixture(true);
        var old=new com.gratilo.payment.api.TipQuote(10000,351,10351,"EUR",new BigDecimal("3.15"),25,9955,true,351,"Historical",5,BigDecimal.ZERO,45);
        var meta=TipPricingSnapshot.metadata(old,true);meta.put("chargeModel","POOL");meta.put("poolId",UUID.randomUUID().toString());
        f.tx.setMetadata(JsonMapper.createDefault().writeValueAsString(meta));f.rows.get(0).setAmountMinor(4978);f.rows.get(1).setAmountMinor(4977);
        f.settle(0);f.settle(1);var before=f.tx.getMetadata();var r=f.read();
        assertEquals("RECONCILED",r.status());assertEquals(45,r.agreedCommissionMinor());assertEquals(45,r.economicNetRevenueMinor());
        assertEquals(before,f.tx.getMetadata());
    }
    @Test void mismatchedSourcesDuplicateTransfersMissingLedgerAndDisputesFailClosed()throws Exception {
        var f=new Fixture(true);f.settle(0);f.settle(1);f.remoteTransfers.get(1).put("source_transaction","ch_other");
        assertEquals("TRANSFER_RECONCILIATION_MISMATCH",f.read().reason());assertNull(f.read().economicNetRevenueMinor());
        f.remoteTransfers.get(1).put("source_transaction","ch_fixture");
        var duplicate=new LinkedHashMap<>(f.remoteTransfers.get(0));duplicate.put("id","tr_duplicate");f.remoteTransfers.add(duplicate);
        assertEquals("RECONCILIATION_REQUIRED",f.read().status());f.remoteTransfers.removeLast();
        f.balances.remove("txn_transfer_1");assertNull(f.read().platformResidualMinor());
        f=new Fixture(true);f.settle(0);f.settle(1);f.disputed=true;
        assertEquals("DISPUTE_LEDGER_REVIEW_REQUIRED",f.read().reason());assertNull(f.read().platformResidualMinor());
    }
    @Test void transferListsArePaginatedAndFrozenTotalMustMatch()throws Exception {
        var f=new Fixture(true);f.settle(0);f.settle(1);f.paginate=true;
        assertEquals("RECONCILED",f.read().status());assertTrue(f.paths.stream().anyMatch(p->p.contains("starting_after=tr_0")));
        f.rows.get(0).setAmountMinor(5000);assertEquals("ALLOCATION_TOTAL_MISMATCH",f.read().reason());
    }
    @Test void feeAuditRetriesOnlyReadingUntilTheWholePoolIsSettled()throws Exception {
        var f=new Fixture(true);f.audit.setStatus("PENDING");
        var repo=StripeFeeAuditTest.proxy(PaymentFeeAuditRepository.class,(p,m,a)->switch(m.getName()){
            case "findById"->Optional.of(f.audit);case "findByStatus"->f.audit.getStatus().equals(a[0])?List.of(f.audit):List.of();
            case "update"->a[0];default->throw new AssertionError(m.getName());});
        var txs=StripeFeeAuditTest.proxy(TransactionRepository.class,(p,m,a)->m.getName().equals("update")?a[0]:Optional.of(f.tx));
        var service=new StripeFeeAuditService(repo,txs,"sk_test_fixture",null,f.repository){@Override protected String get(String path)throws Exception{return f.get(path);}};
        f.settle(0);service.reconcile();assertEquals("POOL_SETTLEMENT_PENDING",f.audit.getStatus());assertNull(f.audit.getPlatformNetRevenueMinor());
        assertEquals(4750,service.report(f.tx.getId()).orElseThrow().poolSettlement().outstandingRecipientsMinor());
        f.settle(1);service.reconcile();assertEquals("MATCHED",f.audit.getStatus());assertEquals(500,f.audit.getPlatformNetRevenueMinor());
        assertEquals(500,service.report(f.tx.getId()).orElseThrow().platformNetRevenueMinor());
        assertEquals(2,f.remoteTransfers.size());
    }

    static class Fixture {
        final TransactionEntity tx=new TransactionEntity();final PaymentFeeAuditEntity audit=new PaymentFeeAuditEntity();
        final com.gratilo.payment.api.TipQuote pricing;final List<PoolTransferEntity> rows=new ArrayList<>();
        final List<Map<String,Object>> remoteTransfers=new ArrayList<>(),refunds=new ArrayList<>();
        final Map<String,List<Map<String,Object>>> reversals=new HashMap<>();final Map<String,Map<String,Object>> balances=new HashMap<>();
        final List<String> paths=new ArrayList<>();final PoolTransferRepository repository;long refunded;boolean disputed,paginate;
        Fixture(boolean covered)throws Exception {
            pricing=TipPricing.poolQuote(10000,covered,new BigDecimal("3.15"),25,TipPricing.DEFAULT_PLATFORM_PERCENT);
            tx.setId(UUID.randomUUID());tx.setAmountMinor(10000);tx.setProvider("STRIPE");tx.setType(TransactionType.TIP);tx.setStatus(TransactionStatus.BOOKED);tx.setProviderReference("cs_test_fixture");
            var meta=TipPricingSnapshot.metadata(pricing,covered);meta.put("chargeModel","POOL");meta.put("poolId",UUID.randomUUID().toString());tx.setMetadata(JsonMapper.createDefault().writeValueAsString(meta));
            audit.setTransactionId(tx.getId());audit.setChargedMinor(pricing.totalAmountMinor());audit.setSurchargeMinor(pricing.serviceFeeMinor());audit.setEstimatedFeeMinor(pricing.estimatedStripeFeeMinor());audit.setPlatformFeeMinor(500L);
            audit.setActualFeeMinor(pricing.estimatedStripeFeeMinor());audit.setStripeChargeId("ch_fixture");audit.setStripePaymentIntentId("pi_fixture");audit.setStatus("MATCHED");
            for(int i=0;i<2;i++){var row=new PoolTransferEntity();row.setId(new UUID(0,i+1));row.setTransactionId(tx.getId());row.setAmountMinor(pricing.recipientAmountMinor()/2);row.setStripeAccountId("acct_"+i);row.setStatus("PENDING");rows.add(row);}
            repository=StripeFeeAuditTest.proxy(PoolTransferRepository.class,(p,m,a)->{assertEquals("findByTransactionId",m.getName());return rows;});
        }
        PoolReconciliation.Report read(){return new PoolReconciliation(repository,this::get).read(tx,audit);}
        void settle(int index){
            var row=rows.get(index);String id="tr_"+index;row.setStripeTransferId(id);row.setStatus("TRANSFERRED");
            var transfer=new LinkedHashMap<String,Object>();transfer.putAll(Map.of("id",id,"livemode",false,"amount",row.getAmountMinor(),"amount_reversed",0,"currency","eur","source_transaction","ch_fixture","destination",row.getStripeAccountId(),"transfer_group","gratilo_"+tx.getId(),"metadata",Map.of("pool_allocation_id",row.getId().toString())));
            transfer.put("balance_transaction","txn_transfer_"+index);remoteTransfers.add(transfer);balances.put("txn_transfer_"+index,balance("txn_transfer_"+index,id,"transfer",-row.getAmountMinor(),0));
        }
        void refund(long amount){
            refunded=amount;refunds.add(new LinkedHashMap<>(Map.of("id","re_fixture","charge","ch_fixture","amount",amount,"currency","eur","status","succeeded","balance_transaction","txn_refund")));
            balances.put("txn_refund",balance("txn_refund","re_fixture","refund",-amount,0));
            long desired=rows.stream().mapToLong(PoolTransferEntity::getAmountMinor).sum()*amount/pricing.totalAmountMinor();
            rows.get(0).setReversalAmountMinor((desired+1)/2);rows.get(1).setReversalAmountMinor(desired/2);
        }
        void reverse(int index,long amount){
            String id="tr_"+index,reversal="trr_"+index,bt="txn_reversal_"+index;
            remoteTransfers.get(index).put("amount_reversed",amount);rows.get(index).setReversedAmountMinor(amount);
            if(amount==rows.get(index).getAmountMinor())rows.get(index).setStatus("REVERSED");
            reversals.put(id,List.of(Map.of("id",reversal,"transfer",id,"amount",amount,"currency","eur","balance_transaction",bt)));
            balances.put(bt,balance(bt,reversal,"transfer_refund",amount,0));
        }
        Map<String,Object> charge(){
            var charge=new LinkedHashMap<String,Object>();charge.putAll(Map.of("id","ch_fixture","livemode",false,"paid",true,"captured",true,"amount",pricing.totalAmountMinor(),"currency","eur","amount_refunded",refunded,"disputed",disputed,"transfer_group","gratilo_"+tx.getId()));
            charge.put("payment_intent","pi_fixture");var bt=balance("txn_charge","ch_fixture","payment",pricing.totalAmountMinor(),audit.getActualFeeMinor());bt.put("fee_details",List.of(Map.of("type","stripe_fee","currency","eur","amount",audit.getActualFeeMinor())));charge.put("balance_transaction",bt);return charge;
        }
        String get(String path)throws Exception {
            paths.add(path);Object response;
            if(path.startsWith("/v1/checkout/sessions/"))response=Map.of("livemode",false,"payment_status","paid","payment_intent","pi_fixture","metadata",Map.of("transaction_id",tx.getId().toString()));
            else if(path.startsWith("/v1/payment_intents/"))response=Map.of("livemode",false,"status","succeeded","latest_charge",charge());
            else if(path.startsWith("/v1/charges/"))response=charge();
            else if(path.startsWith("/v1/transfers?")){var data=remoteTransfers;if(paginate)data=path.contains("starting_after=")?new ArrayList<>(remoteTransfers.subList(1,2)):new ArrayList<>(remoteTransfers.subList(0,1));response=Map.of("data",data,"has_more",paginate&&!path.contains("starting_after="));}
            else if(path.contains("/reversals")){String transfer=path.split("/")[3];response=Map.of("data",reversals.getOrDefault(transfer,List.of()),"has_more",false);}
            else if(path.startsWith("/v1/refunds?"))response=Map.of("data",refunds,"has_more",false);
            else if(path.startsWith("/v1/balance_transactions/")){response=balances.get(path.substring(path.lastIndexOf('/')+1));if(response==null)throw new IllegalStateException("Unknown test ledger entry");}
            else throw new AssertionError("Unexpected Stripe GET");
            return JsonMapper.createDefault().writeValueAsString(response);
        }
        static Map<String,Object> balance(String id,String source,String type,long amount,long fee){return new LinkedHashMap<>(Map.of("id",id,"source",source,"type",type,"currency","eur","amount",amount,"fee",fee,"net",amount-fee,"status","available"));}
    }
}
