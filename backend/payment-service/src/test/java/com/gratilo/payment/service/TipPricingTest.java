package com.gratilo.payment.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TipPricingTest {
    @Test void voluntaryIncreaseCoversFeeCalculatedOnTheFullCharge() {
        long increase = TipPricing.voluntaryIncreaseMinor(500, 1.5, 25);
        assertEquals(33, increase);
        assertEquals(533, 500 + increase);
        assertTrue(increase >= TipPricing.serviceFeeMinor(500 + increase, 1.5, 25));
        assertTrue(increase - 1 < TipPricing.serviceFeeMinor(500 + increase - 1, 1.5, 25));
    }

    @Test void noSurchargeWhenFeeDoesNotCoverFixedChargeAndInputsMustBeValid() {
        assertEquals(0, TipPricing.voluntaryIncreaseMinor(500, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> TipPricing.voluntaryIncreaseMinor(99, 1.5, 25));
        assertThrows(IllegalArgumentException.class, () -> TipPricing.voluntaryIncreaseMinor(500, Double.NaN, 25));
        assertThrows(IllegalArgumentException.class, () -> TipPricing.voluntaryIncreaseMinor(500, 1.5, -1));
    }
    @Test void centAccurateGrossUpIsMinimalAcrossRatesAndAmounts() {
        for(double rate:new double[]{0,1.5,2.5,2.8,3.15,4.99})for(long fixed:new long[]{0,25,40})for(long tip:new long[]{100,101,499,500,1000,9999,1_000_000}) {
            var quote=TipPricing.quote(tip,true,rate,fixed);long surcharge=quote.serviceFeeMinor();
            assertEquals(tip,quote.recipientAmountMinor());assertEquals(tip+surcharge,quote.totalAmountMinor());
            assertTrue(surcharge>=TipPricing.serviceFeeMinor(quote.totalAmountMinor(),rate,fixed));
            if(surcharge>0)assertTrue(surcharge-1<TipPricing.serviceFeeMinor(quote.totalAmountMinor()-1,rate,fixed));
            var uncovered=TipPricing.quote(tip,false,rate,fixed);assertEquals(0,uncovered.serviceFeeMinor());assertEquals(tip,uncovered.totalAmountMinor());
        }
    }
    @Test void configuredInternationalRateMatchesObservedTestChargeFee() {
        assertEquals(58,TipPricing.serviceFeeMinor(1041,3.15,25));
        var quote=TipPricing.quote(1000,true,3.15,25);assertEquals(58,quote.serviceFeeMinor());assertEquals(1058,quote.totalAmountMinor());
    }
}
