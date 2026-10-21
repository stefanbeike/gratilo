package com.gratilo.payment.service;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Actual HTTP request construction exercised against a strictly loopback Stripe fake. */
class StripeRequestScopeTest {
    @Test void accountHeaderVersionAndIdempotencyArePresentAndNeverLeakToPlatformRequests()throws Exception {
        var requests=new ArrayList<Map<String,String>>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/checkout/sessions",exchange->{
            requests.add(Map.of("account",Objects.toString(exchange.getRequestHeaders().getFirst("Stripe-Account"),"PLATFORM"),
                "version",Objects.toString(exchange.getRequestHeaders().getFirst("Stripe-Version"),""),
                "key",Objects.toString(exchange.getRequestHeaders().getFirst("Idempotency-Key"),""),
                "method",exchange.getRequestMethod(),"body",new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)));
            byte[] response="{\"livemode\":false}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,response.length);try(var out=exchange.getResponseBody()){out.write(response);}
        });
        server.start();
        try {
            var checkout=new StripeCheckoutService(null,null,null,"sk_test_fixture","http://localhost/success","http://localhost/cancel",new BigDecimal("3.15"),25,null){
                @Override protected String stripeApiBaseUrl(){return "http://127.0.0.1:"+server.getAddress().getPort();}
            };
            checkout.stripeRequest("/v1/checkout/sessions","mode=payment&payment_intent_data%5Bapplication_fee_amount%5D=277","checkout-frozen","acct_frozen");
            checkout.stripeRequest("/v1/checkout/sessions/cs_test_frozen",null,null,"acct_frozen");
            checkout.stripeRequest("/v1/checkout/sessions/cs_test_legacy",null,null,null);
            assertEquals(List.of("acct_frozen","acct_frozen","PLATFORM"),requests.stream().map(r->r.get("account")).toList());
            assertTrue(requests.stream().allMatch(r->r.get("version").equals("2026-09-30.endive")));
            assertEquals("checkout-frozen",requests.getFirst().get("key"));assertEquals("POST",requests.getFirst().get("method"));
            assertEquals("GET",requests.get(1).get("method"));assertFalse(requests.getFirst().get("body").contains("transfer_data"));
        } finally {server.stop(0);}
    }
}
