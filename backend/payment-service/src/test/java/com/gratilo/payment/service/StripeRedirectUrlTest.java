package com.gratilo.payment.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StripeRedirectUrlTest {
    @Test
    void preservesLocalSandboxRedirectsWithCheckoutPlaceholder() {
        for (String route : new String[] {"success", "cancelled"}) {
            String url = "http://localhost:3001/pay/" + route + "?session_id={CHECKOUT_SESSION_ID}";
            assertEquals(url, StripeCheckoutService.absoluteUrl(url));
        }
    }

    @Test
    void preservesProductionUrlWithStripePlaceholder() {
        String url = "https://gratilo.eu/pay/success?session_id={CHECKOUT_SESSION_ID}";
        assertEquals(url, StripeCheckoutService.absoluteUrl(url));
    }

    @Test
    void rejectsInvalidConfigurationInsteadOfRedirectingToLocalhost() {
        for (String url : new String[] {"/pay/success", "", "ftp://gratilo.eu/pay/success"}) {
            assertThrows(IllegalArgumentException.class, () -> StripeCheckoutService.absoluteUrl(url));
        }
    }
}
