package com.gratilo.payment.service;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class NetSandboxPayoutTest {
    @Test void payoutValidationUsesStripeAvailableRatherThanBookedIncome(){
        assertThrows(IllegalArgumentException.class,()->StripePayoutService.validateAmount(2396,0));
        assertDoesNotThrow(()->StripePayoutService.validateAmount(2396,2396));
    }
}
