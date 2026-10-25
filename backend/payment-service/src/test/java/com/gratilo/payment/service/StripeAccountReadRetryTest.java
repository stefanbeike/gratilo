package com.gratilo.payment.service;

import org.junit.jupiter.api.Test;
import java.net.http.HttpRequest;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class StripeAccountReadRetryTest {
    @Test void accountReadsRetry429WithBoundedBackoffAndStopAfterSuccess()throws Exception{
        AtomicInteger calls=new AtomicInteger();
        var stripe=new StripeConnectService("sk_test_fixture","http://localhost"){
            @Override protected String request(HttpRequest request)throws Exception{
                assertEquals("GET",request.method());int attempt=calls.incrementAndGet();
                if(attempt==1)throw new StripeApiException(429,0,"req_test_1");
                return StripeAccountFixtures.readyJson();
            }
        };
        assertTrue(stripe.retrieveAccount("acct_fixture").contains("acct_fixture"));assertEquals(2,calls.get());
    }
    @Test void accountReadsRetryServerErrorsAtMostTwiceAndDoNotRetry403()throws Exception{
        AtomicInteger calls=new AtomicInteger();
        var limited=new StripeConnectService("sk_test_fixture","http://localhost"){
            @Override protected String request(HttpRequest request)throws Exception{calls.incrementAndGet();throw new StripeApiException(503,0,"req_test_2");}
        };
        var failure=assertThrows(StripeConnectService.StripeApiException.class,()->limited.retrieveAccount("acct_fixture"));
        assertEquals(503,failure.statusCode());assertEquals(3,calls.get());
        calls.set(0);var forbidden=new StripeConnectService("sk_test_fixture","http://localhost"){
            @Override protected String request(HttpRequest request)throws Exception{calls.incrementAndGet();throw new StripeApiException(403,0,"req_test_3");}
        };
        assertThrows(StripeConnectService.StripeApiException.class,()->forbidden.retrieveAccount("acct_fixture"));assertEquals(1,calls.get());
    }
}
