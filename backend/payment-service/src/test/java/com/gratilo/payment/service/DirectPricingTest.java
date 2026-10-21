package com.gratilo.payment.service;

import com.gratilo.payment.domain.TransactionEntity;
import io.micronaut.json.JsonMapper;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DirectPricingTest {
    private static final BigDecimal RATE=new BigDecimal("3.15");
    @Test void exactTwentyFiveEuroFlowsAndApplicationFeeComponents() {
        var covered=TipPricing.quote(2500,true,RATE,25);
        assertEquals(170,covered.platformFeeMinor());assertEquals(107,covered.serviceFeeMinor());
        assertEquals(2607,covered.totalAmountMinor());assertEquals(2330,covered.recipientAmountMinor());
        assertEquals(277,covered.totalAmountMinor()-covered.recipientAmountMinor());
        var uncovered=TipPricing.quote(2500,false,RATE,25);
        assertEquals(170,uncovered.platformFeeMinor());assertEquals(104,uncovered.estimatedStripeFeeMinor());
        assertEquals(0,uncovered.serviceFeeMinor());assertEquals(2500,uncovered.totalAmountMinor());
        assertEquals(2226,uncovered.recipientAmountMinor());assertEquals(274,2500-uncovered.recipientAmountMinor());
    }
    @Test void fixedPlusFivePercentNeverChargesCommissionOnSurcharge() {
        for(long amount:new long[]{100,101,500,1000,2500,10000,1_000_000})for(String percent:List.of("0","1.5","3.15","6.8"))for(boolean covered:new boolean[]{true,false}) {
            var q=TipPricing.quote(amount,covered,new BigDecimal(percent),25);
            long commission=45+BigDecimal.valueOf(amount).multiply(new BigDecimal("0.05")).setScale(0,java.math.RoundingMode.HALF_UP).longValueExact();
            assertEquals(commission,q.platformFeeMinor());
            assertEquals(q.totalAmountMinor(),q.recipientAmountMinor()+commission+(covered?q.serviceFeeMinor():q.estimatedStripeFeeMinor()));
            assertEquals(covered?amount+q.serviceFeeMinor():amount,q.totalAmountMinor());
            assertTrue(q.recipientAmountMinor()>0);
        }
    }
    @Test void poolCommissionIsFixedOnceAndSplitsOnlyFrozenNet() {
        for(boolean covered:new boolean[]{true,false}) {
            var q=TipPricing.poolQuote(2500,covered,RATE,25,45);
            assertEquals(45,q.platformFeeMinor());assertEquals(covered?2455:2351,q.recipientAmountMinor());
            var allocations=PoolAllocation.split(q.recipientAmountMinor(),List.of(new PoolAllocation.Weight(new UUID(0,1),new BigDecimal("50")),new PoolAllocation.Weight(new UUID(0,2),new BigDecimal("50"))));
            assertEquals(covered?List.of(1228L,1227L):List.of(1176L,1175L),allocations.stream().map(PoolAllocation.Share::amountMinor).toList());
            var three=PoolAllocation.split(q.recipientAmountMinor(),List.of(new PoolAllocation.Weight(new UUID(0,1),new BigDecimal("20")),new PoolAllocation.Weight(new UUID(0,2),new BigDecimal("30")),new PoolAllocation.Weight(new UUID(0,3),new BigDecimal("50"))));
            assertEquals(q.recipientAmountMinor(),three.stream().mapToLong(PoolAllocation.Share::amountMinor).sum());
        }
        assertEquals(99,TipPricing.poolQuote(2500,true,RATE,25,99).platformFeeMinor());
    }
    @Test void noPositiveCentAfterAllFeesPreventsCheckoutAndInvalidValuesCannotOverflow() {
        assertEquals(22,TipPricing.quote(100,false,RATE,25).recipientAmountMinor());
        assertEquals(1,TipPricing.quote(100,false,BigDecimal.ZERO,49).recipientAmountMinor());
        assertThrows(TipPricing.NoDistributableTipException.class,()->TipPricing.quote(100,false,BigDecimal.ZERO,50));
        assertThrows(TipPricing.NoDistributableTipException.class,()->TipPricing.poolQuote(100,true,RATE,25,100));
        for(long amount:new long[]{-1,0,99,Long.MAX_VALUE})assertThrows(IllegalArgumentException.class,()->TipPricing.quote(amount,false,RATE,25));
        assertThrows(IllegalArgumentException.class,()->TipPricing.poolQuote(100,false,RATE,25,-1));
        assertThrows(RuntimeException.class,()->TipPricing.quote(2500,true,RATE,Long.MAX_VALUE));
    }
    @Test void snapshotsFreezeRuleCommissionScopeAndNetAndNeverInferLegacyCommission() throws Exception {
        for(boolean covered:new boolean[]{true,false})for(boolean pool:new boolean[]{true,false}) {
            var q=pool?TipPricing.poolQuote(2500,covered,RATE,25,45):TipPricing.quote(2500,covered,RATE,25);
            var metadata=TipPricingSnapshot.metadata(q,covered);
            if(!pool){metadata.put("chargeModel","DIRECT");metadata.put("stripeAccountId","acct_frozen");metadata.put("feesCollector","application");}
            var tx=new TransactionEntity();tx.setAmountMinor(2500);tx.setMetadata(JsonMapper.createDefault().writeValueAsString(metadata));
            var snapshot=TipPricingSnapshot.from(tx);
            assertEquals(q.recipientAmountMinor(),snapshot.recipientMinor());assertEquals(q.platformFeeMinor(),snapshot.platformFeeMinor());
            assertEquals(pool?5:4,snapshot.version());assertEquals(pool?null:"acct_frozen",StripePaymentContext.account(tx));
            if(!pool){metadata.remove("stripeAccountId");tx.setMetadata(JsonMapper.createDefault().writeValueAsString(metadata));assertThrows(IllegalStateException.class,()->StripePaymentContext.account(tx));}
        }
        var old=new TransactionEntity();old.setAmountMinor(2500);old.setMetadata("{\"totalAmountMinor\":2500,\"feeCovered\":false}");
        assertEquals(0,TipPricingSnapshot.from(old).platformFeeMinor());assertEquals(2500,TipPricingSnapshot.from(old).recipientMinor());assertNull(StripePaymentContext.account(old));
        var q=TipPricing.legacyQuote(2500,false,RATE,25);old.setMetadata(JsonMapper.createDefault().writeValueAsString(TipPricingSnapshot.metadata(q,false)));
        assertEquals(2396,TipPricingSnapshot.from(old).recipientMinor());assertEquals(0,TipPricingSnapshot.from(old).platformFeeMinor());
    }
    @Test void directReadinessRequiresVerifiedFeePayerButNotSeparateTransfersOrBankPayouts() throws Exception {
        assertTrue(StripeAccountStatus.directReady("acct_fixture",StripeAccountFixtures.readyJson()));
        for(String path:List.of("configuration.recipient.capabilities.stripe_balance.payouts.status","configuration.recipient.capabilities.stripe_balance.stripe_transfers.status"))
            assertTrue(StripeAccountStatus.directReady("acct_fixture",StripeAccountFixtures.changed(path,"pending",false)));
        assertFalse(StripeAccountStatus.directReady("acct_fixture",StripeAccountFixtures.changed("defaults",null,true)));
        assertFalse(StripeAccountStatus.directReady("acct_fixture",StripeAccountFixtures.changed("defaults.responsibilities.fees_collector","stripe",false)));
        assertFalse(StripeAccountStatus.directReady("acct_fixture",StripeAccountFixtures.changed("configuration.merchant.capabilities.card_payments.status","pending",false)));
    }
}
