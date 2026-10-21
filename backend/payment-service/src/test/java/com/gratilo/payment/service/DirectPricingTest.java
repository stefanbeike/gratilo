package com.gratilo.payment.service;

import com.gratilo.payment.domain.TransactionEntity;
import io.micronaut.json.JsonMapper;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DirectPricingTest {
    private static final BigDecimal RATE=new BigDecimal("3.15");
    @Test void contractualCommissionAndStripeCostContributionAreDistinctAtTenTwentyFiveAndOneHundredEuros() {
        long[][] expected={{200,10,31,32},{210,11,32,33},{230,12,32,33},{1000,50,57,58},{2500,125,104,107},{5000,250,183,188},{10000,500,340,351},{20000,1000,655,676},{50000,2500,1600,1652}};
        for(var row:expected)for(boolean covered:new boolean[]{false,true})for(boolean pool:new boolean[]{false,true}) {
            if(!pool && row[0]==50000) continue;
            var quote=pool?TipPricing.poolQuote(row[0],covered,RATE,25,TipPricing.DEFAULT_PLATFORM_PERCENT):TipPricing.quote(row[0],covered,RATE,25);
            long contribution=covered?row[3]:row[2];
            assertEquals(row[1],quote.platformFeeMinor());
            assertEquals(contribution,covered?quote.serviceFeeMinor():quote.estimatedStripeFeeMinor());
            assertEquals(row[1]+contribution,quote.totalAmountMinor()-quote.recipientAmountMinor());
            assertEquals(row[0]-row[1]-(covered?0:contribution),quote.recipientAmountMinor());
        }
    }
    @Test void exactTwentyFiveEuroFlowsAndApplicationFeeComponents() {
        var covered=TipPricing.quote(2500,true,RATE,25);
        assertEquals(125,covered.platformFeeMinor());assertEquals(107,covered.serviceFeeMinor());
        assertEquals(2607,covered.totalAmountMinor());assertEquals(2375,covered.recipientAmountMinor());
        assertEquals(232,covered.totalAmountMinor()-covered.recipientAmountMinor());
        var uncovered=TipPricing.quote(2500,false,RATE,25);
        assertEquals(125,uncovered.platformFeeMinor());assertEquals(104,uncovered.estimatedStripeFeeMinor());
        assertEquals(0,uncovered.serviceFeeMinor());assertEquals(2500,uncovered.totalAmountMinor());
        assertEquals(2271,uncovered.recipientAmountMinor());assertEquals(229,2500-uncovered.recipientAmountMinor());
    }
    @Test void fivePercentNeverChargesCommissionOnSurcharge() {
        for(long amount:new long[]{100,101,500,1000,2500,10000,1_000_000})for(String percent:List.of("0","1.5","3.15","6.8"))for(boolean covered:new boolean[]{true,false}) {
            var q=TipPricing.quote(amount,covered,new BigDecimal(percent),25);
            long commission=BigDecimal.valueOf(amount).multiply(new BigDecimal("0.05")).setScale(0,java.math.RoundingMode.HALF_UP).longValueExact();
            assertEquals(commission,q.platformFeeMinor());
            assertEquals(q.totalAmountMinor(),q.recipientAmountMinor()+commission+(covered?q.serviceFeeMinor():q.estimatedStripeFeeMinor()));
            assertEquals(covered?amount+q.serviceFeeMinor():amount,q.totalAmountMinor());
            assertTrue(q.recipientAmountMinor()>0);
        }
    }
    @Test void poolCommissionIsFivePercentOnceAndSplitsOnlyFrozenNet() {
        for(boolean covered:new boolean[]{true,false}) {
            var q=TipPricing.poolQuote(2500,covered,RATE,25,TipPricing.DEFAULT_PLATFORM_PERCENT);
            assertEquals(125,q.platformFeeMinor());assertEquals(covered?2375:2271,q.recipientAmountMinor());
            var allocations=PoolAllocation.split(q.recipientAmountMinor(),List.of(new PoolAllocation.Weight(new UUID(0,1),new BigDecimal("50")),new PoolAllocation.Weight(new UUID(0,2),new BigDecimal("50"))));
            assertEquals(covered?List.of(1188L,1187L):List.of(1136L,1135L),allocations.stream().map(PoolAllocation.Share::amountMinor).toList());
            var three=PoolAllocation.split(q.recipientAmountMinor(),List.of(new PoolAllocation.Weight(new UUID(0,1),new BigDecimal("20")),new PoolAllocation.Weight(new UUID(0,2),new BigDecimal("30")),new PoolAllocation.Weight(new UUID(0,3),new BigDecimal("50"))));
            assertEquals(q.recipientAmountMinor(),three.stream().mapToLong(PoolAllocation.Share::amountMinor).sum());
        }

    }
    @Test void noPositiveCentAfterAllFeesPreventsCheckoutAndInvalidValuesCannotOverflow() {
        assertEquals(67,TipPricing.quote(100,false,RATE,25).recipientAmountMinor());
        assertEquals(1,TipPricing.quote(100,false,BigDecimal.ZERO,94).recipientAmountMinor());
        assertThrows(TipPricing.NoDistributableTipException.class,()->TipPricing.quote(100,false,BigDecimal.ZERO,95));
        assertThrows(TipPricing.NoDistributableTipException.class,()->TipPricing.poolQuote(100,false,BigDecimal.ZERO,95,TipPricing.DEFAULT_PLATFORM_PERCENT));
        for(long amount:new long[]{-1,0,99,Long.MAX_VALUE})assertThrows(IllegalArgumentException.class,()->TipPricing.quote(amount,false,RATE,25));
        assertThrows(IllegalArgumentException.class,()->TipPricing.poolQuote(100,false,RATE,25,new BigDecimal("-1")));
        assertThrows(RuntimeException.class,()->TipPricing.quote(2500,true,RATE,Long.MAX_VALUE));
    }
    @Test void variedPoolKeysAlwaysDistributeExactlyTheNetAfterOneCommission() {
        for(var weights:List.of(List.of("50","50"),List.of("70","30"),List.of("20","30","50"),List.of("20","20","20","20","20"))) {
            for(boolean covered:new boolean[]{false,true}) {
                var q=TipPricing.poolQuote(231,covered,RATE,25,TipPricing.DEFAULT_PLATFORM_PERCENT);
                var entries=new ArrayList<PoolAllocation.Weight>();
                for(int i=0;i<weights.size();i++)entries.add(new PoolAllocation.Weight(new UUID(0,i+1),new BigDecimal(weights.get(i))));
                var shares=PoolAllocation.split(q.recipientAmountMinor(),entries);
                assertEquals(12,q.platformFeeMinor());
                assertEquals(q.recipientAmountMinor(),shares.stream().mapToLong(PoolAllocation.Share::amountMinor).sum());
                assertTrue(shares.stream().allMatch(s->s.amountMinor()>0));
                assertEquals(shares,PoolAllocation.split(q.recipientAmountMinor(),entries));
            }
        }
    }
    @Test void snapshotsFreezeRuleCommissionScopeAndNetAndNeverInferLegacyCommission() throws Exception {
        for(boolean covered:new boolean[]{true,false})for(boolean pool:new boolean[]{true,false}) {
            var q=pool?TipPricing.poolQuote(2500,covered,RATE,25,TipPricing.DEFAULT_PLATFORM_PERCENT):TipPricing.quote(2500,covered,RATE,25);
            var metadata=TipPricingSnapshot.metadata(q,covered);
            if(!pool){metadata.put("chargeModel","DIRECT");metadata.put("stripeAccountId","acct_frozen");metadata.put("feesCollector","application");}
            var tx=new TransactionEntity();tx.setAmountMinor(2500);tx.setMetadata(JsonMapper.createDefault().writeValueAsString(metadata));
            var snapshot=TipPricingSnapshot.from(tx);
            assertEquals(q.recipientAmountMinor(),snapshot.recipientMinor());assertEquals(q.platformFeeMinor(),snapshot.platformFeeMinor());
            assertEquals(pool?7:6,snapshot.version());assertEquals(pool?null:"acct_frozen",StripePaymentContext.account(tx));
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
