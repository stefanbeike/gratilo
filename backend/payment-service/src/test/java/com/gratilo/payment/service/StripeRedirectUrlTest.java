package com.gratilo.payment.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StripeRedirectUrlTest {
    @Test
    void preservesProductionUrlWithStripePlaceholder() {
        String url = "https://gratilo.de/pay/success?session_id={CHECKOUT_SESSION_ID}";
        assertEquals(url, StripeCheckoutService.absoluteUrl(url));
    }

    @Test
    void rejectsInvalidConfigurationInsteadOfRedirectingToLocalhost() {
        for (String url : new String[] {"/pay/success", "", "ftp://gratilo.de/pay/success"}) {
            assertThrows(IllegalArgumentException.class, () -> StripeCheckoutService.absoluteUrl(url));
        }
    }
}
