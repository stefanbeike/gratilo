package com.gratilo.payment.service;
import com.gratilo.payment.domain.*;
import com.gratilo.payment.repository.*;
import io.micronaut.json.JsonMapper;
import java.lang.reflect.Proxy;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StripeFeeAuditTest {
 @Test void recordsActualFeeAndDifferenceWithoutChangingPaidAmountOrTip()throws Exception{
  var f=new Fixture(true);f.service.reconcile();
  assertEquals("DIFFERENCE",f.audit.getStatus());assertEquals(41,f.audit.getEstimatedFeeMinor());assertEquals(58L,f.audit.getActualFeeMinor());assertEquals(17L,f.audit.getDifferenceMinor());assertEquals(17L,f.audit.getUncoveredMinor());
  assertEquals(1041,f.audit.getChargedMinor());assertEquals(1000,f.tx.getAmountMinor());
  f.service.reconcile();assertEquals(1,f.completedUpdates); // finalized audits are not resubmitted
 }
 @Test void disabledCoverageChargesNoSurchargeAndStillRecordsRealCosts()throws Exception{
  var f=new Fixture(false);f.service.reconcile();assertEquals(0,f.audit.getSurchargeMinor());assertEquals(1000,f.audit.getChargedMinor());assertEquals(58L,f.audit.getUncoveredMinor());
 }
 @Test void asynchronousBalanceIsRetriedAndLiveOrMismatchedSourcesAreNeverRecorded()throws Exception{
  var f=new Fixture(true);f.noBalance=true;f.service.reconcile();assertEquals("PENDING",f.audit.getStatus());assertEquals("WAITING_BALANCE_TRANSACTION",f.audit.getLastError());
  f.noBalance=false;f.live=true;f.service.reconcile();assertEquals("PENDING",f.audit.getStatus());assertNull(f.audit.getActualFeeMinor());
  f.live=false;f.mismatch=true;f.service.reconcile();assertEquals("PENDING",f.audit.getStatus());assertNull(f.audit.getActualFeeMinor());
  f.mismatch=false;f.service.reconcile();assertEquals("DIFFERENCE",f.audit.getStatus());
 }
 @Test void noAuditRowsMeansNoBackfillOrStripeCallsForHistoricPayments()throws Exception{
  var f=new Fixture(true);f.audit=null;f.service.reconcile();assertEquals(0,f.stripeReads);
 }
 @Test void separatesApplicationFeesFromStripeCosts()throws Exception{
  var f=new Fixture(true);f.applicationFee=10;f.service.reconcile();assertEquals(48L,f.audit.getActualFeeMinor());assertTrue(f.audit.getFeeDetails().contains("application_fee"));
 }
 @Test void quoteSnapshotIsNotRepricedWhenConfigurationChanges()throws Exception{
  var f=new Fixture(true);f.service.snapshot(f.tx,TipPricing.quote(1000,true,3.15,25));assertEquals(1041,f.audit.getChargedMinor());assertEquals(1.5,f.audit.getEstimatePercent());
 }
 @Test void privateFeeReportRequiresTransactionOwnership(){
  var f=new Fixture(true);UUID owner=UUID.randomUUID();
  var service=new TransactionService(null){@Override public Optional<com.gratilo.payment.api.TransactionResponse> get(UUID user,UUID id){return owner.equals(user)&&id.equals(f.tx.getId())?Optional.of(com.gratilo.payment.api.TransactionResponse.from(f.tx)):Optional.empty();}};
  var controller=new com.gratilo.payment.controller.TransactionController(service,f.service);
  assertEquals(io.micronaut.http.HttpStatus.NOT_FOUND,controller.feeReport(f.tx.getId(),io.micronaut.security.authentication.Authentication.build(UUID.randomUUID().toString())).getStatus());
  assertEquals(io.micronaut.http.HttpStatus.OK,controller.feeReport(f.tx.getId(),io.micronaut.security.authentication.Authentication.build(owner.toString())).getStatus());
 }
 @Test void unavailableFeeBreakdownDoesNotClaimZeroProcessingCosts()throws Exception{
  var f=new Fixture(true);f.actualFee=0;f.emptyDetails=true;f.service.reconcile();assertEquals("REPORT_REQUIRED",f.audit.getStatus());assertNull(f.audit.getActualFeeMinor());
 }
 @Test void expiredUnpaidSessionsStopPollingWithoutClaimingProcessingFees()throws Exception{
  var f=new Fixture(true);f.expired=true;f.service.reconcile();assertEquals("NOT_APPLICABLE",f.audit.getStatus());assertNull(f.audit.getActualFeeMinor());
  f.service.reconcile();assertEquals(1,f.stripeReads);
 }
 @Test void matchedEstimateIsMarkedAndKeepsZeroDifference()throws Exception{
  var f=new Fixture(true);f.actualFee=41;f.service.reconcile();assertEquals("MATCHED",f.audit.getStatus());assertEquals(0L,f.audit.getDifferenceMinor());
 }
 static class Fixture{
  final TransactionEntity tx=new TransactionEntity();PaymentFeeAuditEntity audit;int completedUpdates,stripeReads;boolean noBalance,live,mismatch,emptyDetails,expired;long actualFee=58,applicationFee;
  final StripeFeeAuditService service;
  Fixture(boolean covered){
   tx.setId(UUID.randomUUID());tx.setAmountMinor(1000);tx.setProvider("STRIPE");tx.setProviderReference("cs_test_audit");tx.setStatus(TransactionStatus.BOOKED);
   var repo=proxy(PaymentFeeAuditRepository.class,(p,m,a)->switch(m.getName()){
    case "findById"->Optional.ofNullable(audit);case "findByStatus"->audit!=null&&audit.getStatus().equals(a[0])?List.of(audit):List.of();
    case "save"->{audit=(PaymentFeeAuditEntity)a[0];yield audit;}case "update"->{if(!audit.getStatus().equals("PENDING"))completedUpdates++;yield audit;}default->throw new AssertionError(m.getName());});
   var txs=proxy(TransactionRepository.class,(p,m,a)->{assertEquals("findById",m.getName());return Optional.of(tx);});
   service=new StripeFeeAuditService(repo,txs,"sk_test_fixture"){
    @Override protected String get(String path)throws Exception{
     stripeReads++;var mapper=JsonMapper.createDefault();
     if(path.startsWith("/v1/checkout/sessions/"))return mapper.writeValueAsString(Map.of("livemode",live,"payment_status",expired?"unpaid":"paid","status",expired?"expired":"complete","payment_intent","pi_audit","metadata",Map.of("transaction_id",mismatch?UUID.randomUUID().toString():tx.getId().toString())));
     var charge=new HashMap<String,Object>();charge.putAll(Map.of("livemode",false,"paid",true,"captured",true,"currency","eur","amount",audit.getChargedMinor()));
     if(!noBalance){var details=new ArrayList<Map<String,Object>>();details.add(Map.of("type","stripe_fee","amount",actualFee-applicationFee,"currency","eur"));if(applicationFee>0)details.add(Map.of("type","application_fee","amount",applicationFee,"currency","eur"));charge.put("balance_transaction",Map.of("id","txn_audit","currency","eur","amount",audit.getChargedMinor(),"fee",actualFee,"net",audit.getChargedMinor()-actualFee,"fee_details",emptyDetails?List.of():details));}
     return mapper.writeValueAsString(Map.of("livemode",false,"status","succeeded","latest_charge",charge));
    }
   };service.snapshot(tx,TipPricing.quote(1000,covered,1.5,25));
  }
 }
 @SuppressWarnings("unchecked")static<T>T proxy(Class<T> type,java.lang.reflect.InvocationHandler h){return(T)Proxy.newProxyInstance(type.getClassLoader(),new Class[]{type},h);}
}
