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
}
