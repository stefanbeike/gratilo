package com.gratilo.payment.service;

import com.gratilo.payment.domain.TransactionEntity;
import com.gratilo.payment.domain.TransactionType;
import io.micronaut.json.JsonMapper;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class RecipientExtraPricingTest {
    private static final BigDecimal RATE=new BigDecimal("3.15");

    @Test void personalFeeIsGrossAndPlatformPaysStripeCost(){
        for(long[] example:new long[][]{{500,70,430},{1000,95,905},{2000,145,1855}}){
            var q=TipPricing.directRecipientExtraVatQuote(example[0],false,RATE,25,TipPricing.DEFAULT_PLATFORM_PERCENT,TipPricing.DEFAULT_PLATFORM_VAT_PERCENT);
            assertEquals(15,q.pricingVersion());assertEquals(example[1],q.platformFeeMinor());assertEquals(example[2],q.recipientAmountMinor());
            assertEquals(q.totalAmountMinor(),q.platformFeeMinor()+q.recipientAmountMinor());
            assertEquals(45+Math.round(example[0]*0.05),q.platformFeeMinor());
            assertEquals(example[1]-Math.round(example[1]*19.0/119.0),q.platformFeeNetMinor());assertEquals(Math.round(example[1]*19.0/119.0),q.platformFeeTaxMinor());
            var tx=snapshot(q,false,false);var frozen=tx.getMetadata();var p=TipPricingSnapshot.from(tx);
            assertEquals(example[1],p.applicationFeeMinor());assertEquals(example[2],p.recipientMinor());assertEquals(frozen,tx.getMetadata());
        }
    }

    @Test void optionalExtraIsFullyCreditedAndDoesNotIncreaseThePlatformFee(){
        // A 24-cent estimate fixture makes the optional recipient-only amount exactly 41 cents.
        var q=TipPricing.directRecipientExtraVatQuote(500,true,RATE,24,TipPricing.DEFAULT_PLATFORM_PERCENT,TipPricing.DEFAULT_PLATFORM_VAT_PERCENT);
        assertEquals(41,q.serviceFeeMinor());assertEquals(541,q.totalAmountMinor());assertEquals(70,q.platformFeeMinor());assertEquals(471,q.recipientAmountMinor());
        var pool=TipPricing.poolRecipientExtraVatQuote(500,true,RATE,24,TipPricing.DEFAULT_PLATFORM_PERCENT,TipPricing.DEFAULT_PLATFORM_VAT_PERCENT);
        assertEquals(471,pool.recipientAmountMinor());assertEquals(70,pool.platformFeeMinor());
        var shares=PoolAllocation.split(pool.recipientAmountMinor(),List.of(new PoolAllocation.Weight(new UUID(0,1),new BigDecimal("50")),new PoolAllocation.Weight(new UUID(0,2),new BigDecimal("50"))));
        assertEquals(List.of(236L,235L),shares.stream().map(PoolAllocation.Share::amountMinor).toList());
    }

    @Test void poolNetIsCommissionOnlyAndSplitsExactlyForNewPayments(){
        var q=TipPricing.poolRecipientExtraVatQuote(500,false,RATE,25,TipPricing.DEFAULT_PLATFORM_PERCENT,TipPricing.DEFAULT_PLATFORM_VAT_PERCENT);
        assertEquals(16,q.pricingVersion());assertEquals(70,q.platformFeeMinor());assertEquals(430,q.recipientAmountMinor());
        var shares=PoolAllocation.split(q.recipientAmountMinor(),List.of(new PoolAllocation.Weight(new UUID(0,1),new BigDecimal("50")),new PoolAllocation.Weight(new UUID(0,2),new BigDecimal("50"))));
        assertEquals(List.of(215L,215L),shares.stream().map(PoolAllocation.Share::amountMinor).toList());
        assertEquals(430,shares.stream().mapToLong(PoolAllocation.Share::amountMinor).sum());
        var tx=snapshot(q,true,false);assertEquals(16,TipPricingSnapshot.from(tx).version());assertEquals(q.recipientAmountMinor(),TipPricingSnapshot.from(tx).recipientMinor());
    }

    @Test void vatIsExtractedFromTheAgreedGrossFeeInsteadOfAddedToItAndOldVersionsRemainFrozen(){
        var q=TipPricing.directRecipientExtraVatQuote(500,true,RATE,25,TipPricing.DEFAULT_PLATFORM_PERCENT,TipPricing.DEFAULT_PLATFORM_VAT_PERCENT);
        assertEquals(q.platformFeeMinor(),q.platformFeeNetMinor()+q.platformFeeTaxMinor());
        assertEquals(70,q.platformFeeMinor());assertEquals(59,q.platformFeeNetMinor());assertEquals(11,q.platformFeeTaxMinor());
        var old=TipPricing.directApplicationVatQuote(500,false,RATE,25,TipPricing.DEFAULT_PLATFORM_PERCENT,TipPricing.DEFAULT_PLATFORM_VAT_PERCENT);
        assertEquals(12,old.pricingVersion());assertEquals(83,old.platformFeeMinor());
    }

    private static TransactionEntity snapshot(com.gratilo.payment.api.TipQuote q,boolean pool,boolean covered){
        var data=TipPricingSnapshot.metadata(q,covered);data.put("chargeModel",pool?"POOL":"DIRECT");if(pool){data.put("poolId",UUID.randomUUID().toString());}
        else {data.put("stripeAccountId","acct_fixture");data.put("feesCollector","application");}
        var tx=new TransactionEntity();tx.setAmountMinor(q.tipAmountMinor());tx.setType(TransactionType.TIP);try{tx.setMetadata(JsonMapper.createDefault().writeValueAsString(data));}catch(Exception e){throw new RuntimeException(e);}return tx;
    }
}
