package com.gratilo.payment.service;

import com.gratilo.payment.domain.*;
import com.gratilo.payment.repository.*;
import io.micronaut.json.JsonMapper;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DirectFeeAuditTest {
    @Test void changingOnlyTheFrozenCollectorNeverReinterpretsAnOldPricingVersion() throws Exception {
        var f = new Fixture(false);
        var saved = JsonMapper.createDefault().readValue(f.tx.getMetadata(), Map.class);
        saved.put("feesCollector", "stripe");
        f.tx.setMetadata(JsonMapper.createDefault().writeValueAsString(saved));
        f.service.reconcile();
        assertEquals("PENDING", f.audit.getStatus());
        assertNull(f.audit.getActualFeeMinor());
        assertNull(f.audit.getPlatformNetRevenueMinor());
        assertEquals("STRIPE_FEE_RETRY_REQUIRED", f.audit.getLastError());
        assertTrue(f.scopes.isEmpty(), "Invalid frozen context must fail before any external read");
    }

    @Test void stripeCollectedFeesUseConnectedEvidenceAndKeepApplicationFeeAtFivePercent() throws Exception {
        for(boolean covered:new boolean[]{false,true})for(long actual:new long[]{94,114}) {
            var f=new Fixture(covered,true);f.actual=actual;var frozen=f.tx.getMetadata();f.service.reconcile();
            assertEquals(125L,f.audit.getApplicationFeeMinor());assertEquals(actual,f.audit.getActualFeeMinor());
            long charged=covered?2607:2500;
            assertEquals(charged-125-actual,f.audit.getActualNetMinor());
            assertEquals(0L,f.audit.getUncoveredMinor());assertEquals(125L,f.audit.getPlatformNetRevenueMinor());
            var report=f.service.report(f.tx.getId()).orElseThrow();
            assertEquals("stripe",report.feesCollector());assertTrue(report.recipientAmountEstimated());
            assertEquals(charged-125-actual,report.actualRecipientAmountMinor());assertEquals(125,report.platformRetainedMinor());
            assertEquals(frozen,f.tx.getMetadata());
            assertThrows(IllegalArgumentException.class,()->f.service.recordPlatformFeeEvidence(f.tx.getId(),"acct_fixture","ch_fixture",actual,"wrong_cost_owner"));
            assertEquals(List.of("acct_fixture","acct_fixture","PLATFORM","PLATFORM"),f.scopes);
        }
    }
    @Test void unavailableConnectedFeeEvidenceRemainsUnknownRatherThanEstimatedOrZero() throws Exception {
        for(boolean missing:new boolean[]{false,true}) {
            var f=new Fixture(true,true);f.noBalance=missing;f.emptyDetails=!missing;f.service.reconcile();
            assertEquals("PENDING",f.audit.getStatus());assertNull(f.audit.getActualFeeMinor());
            assertNull(f.audit.getActualNetMinor());assertNull(f.audit.getPlatformNetRevenueMinor());
            assertNull(f.service.report(f.tx.getId()).orElseThrow().actualRecipientAmountMinor());
        }
    }
    @Test void stripeCollectedRefundDoesNotClaimFinalPlatformRevenue() throws Exception {
        var f=new Fixture(true,true);f.refunded=1000;f.tx.setStatus(TransactionStatus.REFUNDED);f.service.reconcile();
        assertNull(f.audit.getPlatformNetRevenueMinor());assertEquals(1000L,f.audit.getRefundedMinor());
        assertEquals("POLICY_UNDECIDED",f.audit.getCommissionRefundStatus());
    }

    @Test void platformCostsNeedSeparateEvidenceAndNeverUseConnectedAccountZero()throws Exception {
        var f=new Fixture(true);f.service.reconcile();
        assertEquals("REPORT_REQUIRED",f.audit.getStatus());assertNull(f.audit.getActualFeeMinor());assertNull(f.audit.getPlatformNetRevenueMinor());
        assertEquals(232L,f.audit.getApplicationFeeMinor());assertEquals(125L,f.audit.getPlatformFeeMinor());
        assertEquals(List.of("acct_fixture","acct_fixture","PLATFORM"),f.scopes);
        assertEquals("fee_fixture",f.audit.getStripeApplicationFeeId());
    }
    @Test void platformAbsorbsHigherAndLowerFeesWithoutChangingFrozenRecipient()throws Exception {
        for(boolean covered:new boolean[]{true,false})for(long actual:new long[]{94,114}) {
            var f=new Fixture(covered);f.service.reconcile();
            f.service.recordPlatformFeeEvidence(f.tx.getId(),"acct_fixture","ch_fixture",actual,"stripe_report_fixture");
            long expectedNet=covered?2375:2271;
            assertEquals(expectedNet,TipPricingSnapshot.from(f.tx).recipientMinor());
            assertEquals(125+(covered?107:104)-actual,f.audit.getPlatformNetRevenueMinor());
            assertEquals(actual-(covered?107:104),f.audit.getUncoveredMinor());
            assertEquals("DIFFERENCE",f.audit.getStatus());
            f.service.recordPlatformFeeEvidence(f.tx.getId(),"acct_fixture","ch_fixture",actual,"stripe_report_fixture");
            assertThrows(IllegalStateException.class,()->f.service.recordPlatformFeeEvidence(f.tx.getId(),"acct_fixture","ch_fixture",actual+1,"stripe_report_fixture"));
            assertThrows(IllegalArgumentException.class,()->f.service.recordPlatformFeeEvidence(f.tx.getId(),"acct_other","ch_fixture",actual,"stripe_report_fixture"));
        }
    }
    @Test void refundsNeverClaimKnownPlatformRevenueWithoutCommissionDecision()throws Exception {
        var f=new Fixture(true);f.refunded=1000;f.service.reconcile();
        f.tx.setStatus(TransactionStatus.REFUNDED);
        f.service.recordPlatformFeeEvidence(f.tx.getId(),"acct_fixture","ch_fixture",107,"stripe_report_refund");
        assertNull(f.audit.getPlatformNetRevenueMinor());assertEquals(107L,f.audit.getActualFeeMinor());
        assertEquals("POLICY_UNDECIDED",f.audit.getCommissionRefundStatus());
    }
    @Test void wrongApplicationFeeOrAccountIsNeverAccepted()throws Exception {
        var f=new Fixture(true);f.wrongAccount=true;f.service.reconcile();
        assertEquals("PENDING",f.audit.getStatus());assertNull(f.audit.getActualFeeMinor());
        assertEquals("STRIPE_FEE_RETRY_REQUIRED",f.audit.getLastError());
    }
    static class Fixture {
        final TransactionEntity tx=new TransactionEntity();PaymentFeeAuditEntity audit;long refunded;boolean wrongAccount;long actual=107;boolean noBalance,emptyDetails;
        final List<String> scopes=new ArrayList<>();final StripeFeeAuditService service;
        Fixture(boolean covered)throws Exception {this(covered,false);}
        Fixture(boolean covered,boolean stripeCollector)throws Exception {
            tx.setId(UUID.randomUUID());tx.setProvider("STRIPE");tx.setAmountMinor(2500);tx.setProviderReference("cs_test_fixture");tx.setStatus(TransactionStatus.BOOKED);
            var quote=stripeCollector?TipPricing.directStripeQuote(2500,covered,new BigDecimal("3.15"),25,TipPricing.DEFAULT_PLATFORM_PERCENT):TipPricing.quote(2500,covered,new BigDecimal("3.15"),25);
            var meta=TipPricingSnapshot.metadata(quote,covered);meta.put("chargeModel","DIRECT");meta.put("stripeAccountId","acct_fixture");meta.put("feesCollector",stripeCollector?"stripe":"application");
            tx.setMetadata(JsonMapper.createDefault().writeValueAsString(meta));
            var repo=StripeFeeAuditTest.proxy(PaymentFeeAuditRepository.class,(p,m,a)->switch(m.getName()){
                case "findById"->Optional.ofNullable(audit);case "findByStatus"->audit!=null&&audit.getStatus().equals(a[0])?List.of(audit):List.of();
                case "save"->{audit=(PaymentFeeAuditEntity)a[0];yield audit;}case "update"->a[0];default->throw new AssertionError(m.getName());});
            var txs=StripeFeeAuditTest.proxy(TransactionRepository.class,(p,m,a)->m.getName().equals("update")?a[0]:Optional.of(tx));
            service=new StripeFeeAuditService(repo,txs,"sk_test_fixture") {
                @Override protected String get(String path,String account)throws Exception {assertEquals("acct_fixture",account);scopes.add(account);return fake(path);}
                @Override protected String get(String path)throws Exception {assertTrue(path.startsWith("/v1/application_fees/")||path.startsWith("/v1/balance_transactions/"));scopes.add("PLATFORM");return fake(path);}
                private String fake(String path)throws Exception {
                    Object result;
                    if(path.startsWith("/v1/checkout/sessions/"))result=Map.of("livemode",false,"payment_status","paid","payment_intent","pi_fixture","metadata",Map.of("transaction_id",tx.getId().toString()));
                    else if(path.startsWith("/v1/application_fees/"))result=Map.of("id","fee_fixture","livemode",false,"currency","eur","account",wrongAccount?"acct_other":"acct_fixture","charge","ch_fixture","amount",stripeCollector?125:quote.totalAmountMinor()-quote.recipientAmountMinor(),"amount_refunded",0,"balance_transaction","txn_application");
                    else if(path.startsWith("/v1/balance_transactions/"))result=Map.of("id","txn_application","currency","eur","source","fee_fixture","amount",125,"fee",0,"net",125);
                    else result=Map.of("livemode",false,"status","succeeded","latest_charge",Map.of("id","ch_fixture","livemode",false,"paid",true,"captured",true,"currency","eur","amount",quote.totalAmountMinor(),"amount_refunded",refunded,"application_fee","fee_fixture","balance_transaction",stripeCollector?(noBalance?Map.of():Map.of("id","txn_connected","currency","eur","source","ch_fixture","amount",quote.totalAmountMinor(),"fee",125+actual,"net",quote.totalAmountMinor()-125-actual,"fee_details",emptyDetails?List.of():List.of(Map.of("type","application_fee","amount",125,"currency","eur"),Map.of("type","stripe_fee","amount",actual,"currency","eur")))):Map.of("fee",0)));
                    return JsonMapper.createDefault().writeValueAsString(result);
                }
            };
            service.snapshot(tx,quote);
        }
    }
}
