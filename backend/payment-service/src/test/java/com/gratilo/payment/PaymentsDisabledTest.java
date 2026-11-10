package com.gratilo.payment;

import com.gratilo.payment.config.PaymentsFilter;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PaymentsDisabledTest {
    @Test void blocksEveryPaymentAndLedgerWriteBeforeControllers() {
        var filter = new PaymentsFilter(false);
        for (String path : new String[]{"tips/id/checkout?demo=true", "payouts",
                "connect-account/onboarding/start", "connect-account/onboarding/demo-complete",
                "connect-account/sync", "webhooks/stripe", "transactions", "transactions/id/refund"}) {
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
                    filter.filter(HttpRequest.POST("/payment-service/" + path, "{}")).getStatus());
        }
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, filter.filter(HttpRequest.GET(
                "/payment-service/tips/checkout/confirm?sessionId=unused")).getStatus());
    }

    @Test void keepsOnlyPersistedAccountAndTransactionReadsAvailable() {
        var filter = new PaymentsFilter(false);
        for (String path : new String[]{"transactions", "transactions/summary", "transactions/id", "connect-account"})
            assertNull(filter.filter(HttpRequest.GET("/payment-service/" + path)));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, filter.filter(HttpRequest.POST(
                "/payment-service/transactions/summary", "{}")).getStatus());
    }
    @Test void onlyExactInternalPoolClosurePostBypassesTheDisabledPaymentFilter() {
        var filter = new PaymentsFilter(false);
        String path="/payment-service/internal/pools/"+java.util.UUID.randomUUID()+"/freeze";
        assertNull(filter.filter(HttpRequest.POST(path,"")));
        for(var request:java.util.List.of(HttpRequest.GET(path),HttpRequest.DELETE(path),HttpRequest.POST(path+"/extra",""),HttpRequest.POST("/payment-service/internal/pools/invalid/freeze","")))
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE,filter.filter(request).getStatus());
    }
}
