package com.gratilo.payment.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TipPricingTest {
    @Test void voluntaryIncreaseCoversFeeCalculatedOnTheFullCharge() {
        long increase = TipPricing.voluntaryIncreaseMinor(500, new java.math.BigDecimal("1.5"), 25);
        assertEquals(33, increase);
        assertEquals(533, 500 + increase);
        assertTrue(increase >= TipPricing.serviceFeeMinor(500 + increase, new java.math.BigDecimal("1.5"), 25));
        assertTrue(increase - 1 < TipPricing.serviceFeeMinor(500 + increase - 1, new java.math.BigDecimal("1.5"), 25));
    }

    @Test void noSurchargeWhenFeeDoesNotCoverFixedChargeAndInputsMustBeValid() {
        assertEquals(0, TipPricing.voluntaryIncreaseMinor(500, java.math.BigDecimal.ZERO, 0));
        assertThrows(IllegalArgumentException.class, () -> TipPricing.voluntaryIncreaseMinor(99, new java.math.BigDecimal("1.5"), 25));
        assertThrows(IllegalArgumentException.class, () -> TipPricing.voluntaryIncreaseMinor(500, (java.math.BigDecimal) null, 25));
        assertThrows(IllegalArgumentException.class, () -> TipPricing.voluntaryIncreaseMinor(500, new java.math.BigDecimal("1.5"), -1));
    }
    @Test void centAccurateGrossUpIsMinimalAcrossRatesAndAmounts() {
        for(var rate:java.util.List.of("0","1.5","2.5","2.8","3.15","4.99").stream().map(java.math.BigDecimal::new).toList())for(long fixed:new long[]{0,25,40})for(long tip:new long[]{100,101,499,500,1000,9999,1_000_000}) {
            var quote=TipPricing.legacyQuote(tip,true,rate,fixed);long surcharge=quote.serviceFeeMinor();
            assertEquals(tip,quote.recipientAmountMinor());assertEquals(tip+surcharge,quote.totalAmountMinor());
            assertTrue(surcharge>=TipPricing.serviceFeeMinor(quote.totalAmountMinor(),rate,fixed));
            if(surcharge>0)assertTrue(surcharge-1<TipPricing.serviceFeeMinor(quote.totalAmountMinor()-1,rate,fixed));
            var uncovered=TipPricing.legacyQuote(tip,false,rate,fixed);assertEquals(0,uncovered.serviceFeeMinor());assertEquals(tip,uncovered.totalAmountMinor());assertEquals(tip-uncovered.estimatedStripeFeeMinor(),uncovered.recipientAmountMinor());
        }
    }
    @Test void configuredInternationalRateMatchesObservedTestChargeFee() {
        assertEquals(58,TipPricing.serviceFeeMinor(1041,new java.math.BigDecimal("3.15"),25));
        assertEquals(106,TipPricing.serviceFeeMinor(2563,new java.math.BigDecimal("3.15"),25));
        var quote=TipPricing.legacyQuote(1000,true,new java.math.BigDecimal("3.15"),25);assertEquals(58,quote.serviceFeeMinor());assertEquals(1058,quote.totalAmountMinor());
    }
    @Test void newReferenceGrossUpCoversTenAndTwentyFiveEuroTipsWithoutChangingRecipientAmount() {
        var rate = new java.math.BigDecimal(TipPricing.DEFAULT_PERCENT);
        for(long tip:new long[]{1000,2500}) {
            var quote=TipPricing.legacyQuote(tip,true,rate,25);
            assertEquals(tip==1000?58:107,quote.serviceFeeMinor());
            assertEquals(tip,quote.totalAmountMinor()-TipPricing.serviceFeeMinor(quote.totalAmountMinor(),rate,25));
            assertEquals(tip,quote.recipientAmountMinor());assertTrue(quote.feeEstimated());
            assertTrue(quote.feeEstimateBasis().contains("Abweichungen trägt Gratilo"));
            var uncovered=TipPricing.legacyQuote(tip,false,rate,25);assertEquals(0,uncovered.serviceFeeMinor());assertEquals(tip,uncovered.totalAmountMinor());assertEquals(tip-uncovered.estimatedStripeFeeMinor(),uncovered.recipientAmountMinor());
        }
    }
    @Test void nonCoveredTwentyFiveEurosProducesTheAgreedNetAndSmallTipsRemainPositive() {
        var rate=new java.math.BigDecimal("3.15");var quote=TipPricing.legacyQuote(2500,false,rate,25);
        assertEquals(2500,quote.totalAmountMinor());assertEquals(0,quote.serviceFeeMinor());
        assertEquals(104,quote.estimatedStripeFeeMinor());assertEquals(2396,quote.recipientAmountMinor());
        assertEquals(72,TipPricing.legacyQuote(100,false,rate,25).recipientAmountMinor());
        assertEquals(1,TipPricing.legacyQuote(100,false,java.math.BigDecimal.ZERO,99).recipientAmountMinor());
        assertThrows(TipPricing.NoDistributableTipException.class,()->TipPricing.legacyQuote(100,false,java.math.BigDecimal.ZERO,100));
        assertThrows(TipPricing.NoDistributableTipException.class,()->TipPricing.legacyQuote(100,false,java.math.BigDecimal.ZERO,101));
    }
    @Test void rejectsInvalidRatesAndAmountsAndCannotOverflowOrQuoteBeyondStripeLimit() {
        for(String rate:java.util.List.of("-0.01","100","101"))
            assertThrows(IllegalArgumentException.class,()->TipPricing.legacyQuote(2500,true,new java.math.BigDecimal(rate),25));
        assertThrows(IllegalArgumentException.class,()->TipPricing.legacyQuote(-1,true,java.math.BigDecimal.ONE,25));
        assertThrows(IllegalArgumentException.class,()->TipPricing.legacyQuote(Long.MAX_VALUE,true,java.math.BigDecimal.ONE,25));
        assertThrows(RuntimeException.class,()->TipPricing.legacyQuote(2500,true,java.math.BigDecimal.ONE,Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class,()->TipPricing.legacyQuote(1_000_000,true,new java.math.BigDecimal("99.9999"),25));
    }
}
