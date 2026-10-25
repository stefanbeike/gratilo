package com.gratilo.payment.service;

import io.micronaut.json.JsonMapper;
import org.junit.jupiter.api.Test;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import static org.junit.jupiter.api.Assertions.*;

class StripePricingOnboardingTest {
    @Test void previewIsIsolatedToNewAccountCreationAndUsesPlatformFeesWithStripeLosses() throws Exception {
        for(boolean enabled:new boolean[]{false,true}) {
            var requests=new ArrayList<HttpRequest>();
            var service=new StripeConnectService("sk_test_fixture","http://localhost:3000",false,enabled) {
                @Override protected String request(HttpRequest request) throws Exception {
                    requests.add(request);
                    if(request.uri().getPath().equals("/v2/core/accounts")) {
                        var response=JsonMapper.createDefault().readValue(body(request),Map.class);
                        response.put("id","acct_fixture");response.put("livemode",false);response.put("object","v2.core.account");
                        return JsonMapper.createDefault().writeValueAsString(response);
                    }
                    return "{}";
                }
            };
            service.createAccount("test@example.test","http://localhost:3000/pay/test");
            service.retrieveAccount("acct_existing");
            service.retrieveEvent("evt_fixture");
            service.createOnboardingLink("acct_existing");
            var create=requests.getFirst();
            assertEquals(enabled?StripeConnectService.STRIPE_PRICING_VERSION:StripeConnectService.STABLE_VERSION,create.headers().firstValue("Stripe-Version").orElseThrow());
            var payload=JsonMapper.createDefault().readValue(body(create),Map.class);
            var defaults=(Map<?,?>)payload.get("defaults");
            var responsibility=(Map<?,?>)defaults.get("responsibilities");
            assertEquals("express",payload.get("dashboard"));
            assertEquals("application",responsibility.get("fees_collector"));
            assertEquals(enabled?"stripe":"application",responsibility.get("losses_collector"));
            for(var request:requests.subList(1,requests.size())) {
                assertNotEquals(StripeConnectService.STRIPE_PRICING_VERSION,request.headers().firstValue("Stripe-Version").orElse(""));
                if(request.uri().getPath().startsWith("/v2/"))assertEquals(StripeConnectService.STABLE_VERSION,request.headers().firstValue("Stripe-Version").orElseThrow());
                if(request.method().equals("POST"))assertFalse(body(request).contains("responsibilities"),"Existing account must not be repriced");
            }
        }
    }

    @Test void previewRejectionOrUnexpectedCollectorNeverFallsBackToAnotherAccountModel() {
        for(boolean reject:new boolean[]{false,true}) {
            var calls=new java.util.concurrent.atomic.AtomicInteger();
            var service=new StripeConnectService("sk_test_fixture","http://localhost:3000",false,true) {
                @Override protected String request(HttpRequest request) {
                    calls.incrementAndGet();
                    if(reject)throw new IllegalStateException("Stripe HTTP 400: untrusted provider text");
                    return "{\"id\":\"acct_fixture\",\"object\":\"v2.core.account\",\"livemode\":false,\"dashboard\":\"express\",\"defaults\":{\"responsibilities\":{\"fees_collector\":\"application\",\"losses_collector\":\"application\"}}}";
                }
            };
            var error=assertThrows(IllegalStateException.class,()->service.createAccount("test@example.test","http://localhost"));
            assertTrue(error.getMessage().contains("Plattform-Gebührenverantwortung"));
            assertFalse(error.getMessage().contains("untrusted"));assertEquals(1,calls.get());
        }
    }

    @Test void oldStripeOwnedFeeCollectorFlagFailsBeforeCreatingAnIncompatibleAccount(){
        var service=new StripeConnectService("sk_test_fixture","http://localhost:3000",true);
        var error=assertThrows(IllegalStateException.class,()->service.createAccount("test@example.test","http://localhost"));
        assertTrue(error.getMessage().contains("STRIPE_CONNECT_PLATFORM_FEES_ENABLED"));
    }

    private static String body(HttpRequest request) throws Exception {
        var done=new CompletableFuture<String>();var buffer=new java.io.ByteArrayOutputStream();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            public void onSubscribe(Flow.Subscription s){s.request(Long.MAX_VALUE);}
            public void onNext(ByteBuffer b){byte[] bytes=new byte[b.remaining()];b.get(bytes);buffer.writeBytes(bytes);}
            public void onError(Throwable e){done.completeExceptionally(e);}
            public void onComplete(){done.complete(buffer.toString(StandardCharsets.UTF_8));}
        });
        return done.get(1,TimeUnit.SECONDS);
    }
}
