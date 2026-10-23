package com.gratilo.payment.service;

import com.gratilo.payment.api.TipQuote;
import com.gratilo.payment.domain.*;
import io.micronaut.json.JsonMapper;
import java.math.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DirectPricingTest {
 private static final BigDecimal RATE=new BigDecimal("3.15");
 @Test void fixedPlusFivePercentUsesOriginalTipExactlyOnceForAllChargeModels()throws Exception {
  long[][] examples={{200,55},{210,56},{230,57},{500,70},{1000,95},{2000,145},{2500,170},{10000,545},{20000,1045},{30000,1545},{50000,2545}};
  for(var example:examples)for(boolean covered:new boolean[]{false,true})for(int model=0;model<3;model++){
   long tip=example[0];TipQuote q=quote(model,tip,covered);
   assertEquals(example[1],q.platformFeeMinor());assertEquals(45,q.platformFeeMinor()-TipPricing.platformFee(tip,TipPricing.DEFAULT_PLATFORM_PERCENT));
   assertEquals(covered?tip+q.serviceFeeMinor():tip,q.totalAmountMinor());
   assertEquals(tip-example[1]-(covered?0:q.estimatedStripeFeeMinor()),q.recipientAmountMinor());
   var metadata=TipPricingSnapshot.metadata(q,covered);
   if(model!=1){metadata.put("chargeModel","DIRECT");metadata.put("stripeAccountId","acct_fixture");metadata.put("feesCollector",model==2?"stripe":"application");}else metadata.put("chargeModel","POOL");
   var tx=new TransactionEntity();tx.setAmountMinor(tip);tx.setMetadata(JsonMapper.createDefault().writeValueAsString(metadata));var saved=tx.getMetadata();
   var pricing=TipPricingSnapshot.from(tx);assertQuoteAmounts(q,pricing.quote());assertEquals(model==2?q.platformFeeMinor():q.totalAmountMinor()-q.recipientAmountMinor(),pricing.applicationFeeMinor());
   assertEquals(model==1?null:"acct_fixture",StripePaymentContext.account(tx));assertEquals(saved,tx.getMetadata());
  }
 }
 @Test void exactTwentyFiveEuroFlowsKeepCommissionAndStripeContributionSeparate(){
  for(int model=0;model<3;model++){
   var covered=quote(model,2500,true);assertEquals(170,covered.platformFeeMinor());assertEquals(107,covered.serviceFeeMinor());assertEquals(2607,covered.totalAmountMinor());assertEquals(2330,covered.recipientAmountMinor());
   var uncovered=quote(model,2500,false);assertEquals(170,uncovered.platformFeeMinor());assertEquals(104,uncovered.estimatedStripeFeeMinor());assertEquals(2500,uncovered.totalAmountMinor());assertEquals(2226,uncovered.recipientAmountMinor());
  }
 }
 @Test void roundingAndVariousStripeTariffsNeverChangeTheCommissionBasis(){
  for(long tip:new long[]{100,101,210,230,500,1000,2500,10000})for(String rate:List.of("0","1.5","3.15","6.8"))for(boolean covered:new boolean[]{false,true}){
   var q=TipPricing.quote(tip,covered,new BigDecimal(rate),25);
   long commission=45+BigDecimal.valueOf(tip).multiply(new BigDecimal("0.05")).setScale(0,RoundingMode.HALF_UP).longValueExact();
   assertEquals(commission,q.platformFeeMinor());assertEquals(q.totalAmountMinor(),q.recipientAmountMinor()+commission+(covered?q.serviceFeeMinor():q.estimatedStripeFeeMinor()));
  }
 }
 @Test void zeroOrNegativeRecipientAndOverflowingAmountsAreRejected(){
  assertEquals(22,TipPricing.quote(100,false,RATE,25).recipientAmountMinor());
  assertEquals(1,TipPricing.quote(100,false,BigDecimal.ZERO,49).recipientAmountMinor());
  assertThrows(TipPricing.NoDistributableTipException.class,()->TipPricing.quote(100,false,BigDecimal.ZERO,50));
  for(long amount:new long[]{-1,0,99,Long.MAX_VALUE})assertThrows(IllegalArgumentException.class,()->TipPricing.quote(amount,false,RATE,25));
  assertThrows(RuntimeException.class,()->TipPricing.quote(2500,true,RATE,Long.MAX_VALUE));
 }
 @Test void twoThreeAndFiveMembersReceiveTheEntireFrozenNetWithDeterministicCents(){
  for(var weights:List.of(List.of("50","50"),List.of("50","30","20"),List.of("33.33","33.33","33.34"),List.of("20","20","20","20","20")))for(boolean covered:new boolean[]{false,true}){
   var q=quote(1,2500,covered);var entries=new ArrayList<PoolAllocation.Weight>();for(int i=0;i<weights.size();i++)entries.add(new PoolAllocation.Weight(new UUID(0,i+1),new BigDecimal(weights.get(i))));
   var allocations=PoolAllocation.split(q.recipientAmountMinor(),entries);assertEquals(q.recipientAmountMinor(),allocations.stream().mapToLong(PoolAllocation.Share::amountMinor).sum());assertEquals(allocations,PoolAllocation.split(q.recipientAmountMinor(),entries));assertEquals(170,q.platformFeeMinor());
  }
 }
 @Test void allHistoricalPercentageOnlyVersionsKeepZeroFixedFee()throws Exception{
  for(int version:new int[]{6,7,8})for(boolean covered:new boolean[]{false,true}){
   var q=HistoricalPricingFixtures.build(2500,covered,RATE,25,version);var tx=new TransactionEntity();tx.setAmountMinor(2500);var meta=TipPricingSnapshot.metadata(q,covered);
   if(version!=7){meta.put("chargeModel","DIRECT");meta.put("stripeAccountId","acct_old");meta.put("feesCollector",version==8?"stripe":"application");}
   tx.setMetadata(JsonMapper.createDefault().writeValueAsString(meta));var frozen=tx.getMetadata();assertEquals(125,TipPricingSnapshot.from(tx).platformFeeMinor());assertQuoteAmounts(q,TipPricingSnapshot.from(tx).quote());
   quote(1,2500,covered);assertEquals(frozen,tx.getMetadata());
  }
 }
    @Test void directReadinessRequiresVerifiedFeePayerButNotSeparateTransfersOrBankPayouts() throws Exception {
        assertTrue(StripeAccountStatus.directReady("acct_fixture",StripeAccountFixtures.readyJson()));
        for(String path:List.of("configuration.recipient.capabilities.stripe_balance.payouts.status","configuration.recipient.capabilities.stripe_balance.stripe_transfers.status"))
            assertTrue(StripeAccountStatus.directReady("acct_fixture",StripeAccountFixtures.changed(path,"pending",false)));
        assertFalse(StripeAccountStatus.directReady("acct_fixture",StripeAccountFixtures.changed("defaults",null,true)));
        assertTrue(StripeAccountStatus.directReady("acct_fixture",StripeAccountFixtures.changed("defaults.responsibilities.fees_collector","stripe",false)));
        assertFalse(StripeAccountStatus.directReady("acct_fixture",StripeAccountFixtures.changed("defaults.responsibilities.fees_collector","unknown",false)));
        assertFalse(StripeAccountStatus.directReady("acct_fixture",StripeAccountFixtures.changed("configuration.merchant.capabilities.card_payments.status","pending",false)));
    }

 private static void assertQuoteAmounts(TipQuote expected,TipQuote actual){
  assertEquals(expected.tipAmountMinor(),actual.tipAmountMinor());assertEquals(expected.totalAmountMinor(),actual.totalAmountMinor());assertEquals(expected.recipientAmountMinor(),actual.recipientAmountMinor());assertEquals(expected.platformFeeMinor(),actual.platformFeeMinor());assertEquals(expected.estimatedStripeFeeMinor(),actual.estimatedStripeFeeMinor());assertEquals(expected.serviceFeeMinor(),actual.serviceFeeMinor());assertEquals(expected.pricingVersion(),actual.pricingVersion());assertEquals(expected.feesCollector(),actual.feesCollector());assertEquals(0,expected.platformFeePercent().compareTo(actual.platformFeePercent()));
 }
 private static TipQuote quote(int model,long tip,boolean covered){return switch(model){case 1->TipPricing.poolQuote(tip,covered,RATE,25,TipPricing.DEFAULT_PLATFORM_PERCENT);case 2->TipPricing.directStripeQuote(tip,covered,RATE,25,TipPricing.DEFAULT_PLATFORM_PERCENT);default->TipPricing.quote(tip,covered,RATE,25);};}
}
