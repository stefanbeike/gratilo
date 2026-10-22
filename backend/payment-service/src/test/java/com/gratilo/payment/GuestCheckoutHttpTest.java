package com.gratilo.payment;

import com.gratilo.payment.api.*;
import com.gratilo.payment.service.StripeCheckoutService;
import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.server.EmbeddedServer;
import org.junit.jupiter.api.Test;
import java.net.http.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GuestCheckoutHttpTest {
    @Test void productionSandboxRuleAllowsOnlyPublicLimitsWithoutLogin()throws Exception {
        var props=new HashMap<String,Object>();
        props.put("payments.enabled",true);props.put("payments.test-user-ids",UUID.randomUUID().toString());
        props.put("stripe.secret-key","sk_test_fixture");props.put("stripe.webhook-secret","whsec_fixture");
        props.put("internal.api-key","private-internal-test");props.put("micronaut.server.port",-1);
        props.put("micronaut.security.token.jwt.signatures.secret.validation.secret","private-jwt-test-key-at-least-thirty-two-characters");
        props.put("datasources.default.enabled",false);props.put("jpa.default.enabled",false);
        props.put("liquibase.enabled",false);props.put("liquibase.datasources.default.enabled",false);
        var service=new StripeCheckoutService(null,null,null,"sk_test_fixture","http://localhost/success","http://localhost/cancel",new java.math.BigDecimal("3.15"),25,null);
        try(var context=ApplicationContext.builder().properties(props).environments("test","prd").build()){
            context.registerSingleton(StripeCheckoutService.class,service);context.start();
            assertTrue(context.containsBean(com.gratilo.payment.config.StripeTestAccessRule.class));
            var server=context.getBean(EmbeddedServer.class).start();var client=HttpClient.newHttpClient();
            var limits=server.getURI().resolve("/payment-service/tips/pool-limits");
            var response=client.send(HttpRequest.newBuilder(limits).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,response.statusCode());
            assertEquals(Map.of("minAmountMinor",200,"maxAmountMinor",50000,"currency","EUR"),io.micronaut.json.JsonMapper.createDefault().readValue(response.body(),Map.class));
            for(String path:List.of("/payment-service/transactions","/payment-service/payouts","/payment-service/connect-account","/payment-service/connect-account/balance","/payment-service/tips/pool-limits/private")){
                int status=client.send(HttpRequest.newBuilder(server.getURI().resolve(path)).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode();
                assertTrue(status==401||status==403,"Protected route "+path+": "+status);
            }
            assertEquals(401,client.send(HttpRequest.newBuilder(limits).POST(HttpRequest.BodyPublishers.ofString("{}")).build(),HttpResponse.BodyHandlers.discarding()).statusCode());
            String validToken=jwt("private-jwt-test-key-at-least-thirty-two-characters",System.currentTimeMillis()/1000+600);
            for(String token:List.of("invalid-token",jwt("private-jwt-test-key-at-least-thirty-two-characters",System.currentTimeMillis()/1000-600),validToken)){
                int status=client.send(HttpRequest.newBuilder(limits).header("Authorization","Bearer "+token).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode();
                if(token.equals(validToken))assertEquals(200,status);
                else assertTrue(status==200||status==401); // Token rejection is distinct from public authorization.
                int privateStatus=client.send(HttpRequest.newBuilder(server.getURI().resolve("/payment-service/connect-account/balance")).header("Authorization","Bearer "+token).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode();
                assertTrue(privateStatus==401||privateStatus==403);
            }
        }
    }
    private static String jwt(String secret,long expires)throws Exception{
        var b64=Base64.getUrlEncoder().withoutPadding();
        String data=b64.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8))+"."+b64.encodeToString(("{\"sub\":\"11111111-1111-1111-1111-111111111111\",\"exp\":"+expires+"}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var mac=javax.crypto.Mac.getInstance("HmacSHA256");mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8),"HmacSHA256"));
        return data+"."+b64.encodeToString(mac.doFinal(data.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    @Test void poolLimitsAndInvalidRawAmountsAreEnforcedOverAnonymousHttp()throws Exception {
        var service=new StripeCheckoutService(null,null,null,"sk_test_fixture","http://localhost/success","http://localhost/cancel",new java.math.BigDecimal("3.15"),25,null){
            @Override public Optional<CheckoutResponse> createPool(UUID owner,TipPaymentRequest request){
                return Optional.of(new CheckoutResponse("https://checkout.stripe.com/fake",UUID.randomUUID().toString(),false,poolQuote(request.tipAmountMinor(),request.feeCovered())));
            }
        };
        var props=new HashMap<String,Object>();props.put("payments.enabled",true);props.put("features.tip-pools-enabled",true);props.put("stripe.secret-key","sk_test_fixture");props.put("stripe.webhook-secret","whsec_fixture");props.put("micronaut.server.port",-1);
        props.put("datasources.default.enabled",false);props.put("jpa.default.enabled",false);props.put("liquibase.enabled",false);props.put("liquibase.datasources.default.enabled",false);
        try(var context=ApplicationContext.builder().properties(props).environments("test").build()){
            context.registerSingleton(StripeCheckoutService.class,service);context.start();var server=context.getBean(EmbeddedServer.class).start();var client=HttpClient.newHttpClient();
            var limits=client.send(HttpRequest.newBuilder(server.getURI().resolve("/payment-service/tips/pool-limits")).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,limits.statusCode());assertTrue(limits.body().contains("\"minAmountMinor\":200"));assertTrue(limits.body().contains("\"maxAmountMinor\":50000"));
            String path="/payment-service/tips/pools/"+UUID.randomUUID();
            for(String amount:List.of("199","50001","-1","0","200.1","null","\"invalid\"","9223372036854775808")){
                var response=client.send(HttpRequest.newBuilder(server.getURI().resolve(path+"/checkout")).header("Content-Type","application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"tipAmountMinor\":"+amount+",\"idempotencyKey\":\"test-raw\"}")).build(),HttpResponse.BodyHandlers.ofString());
                assertTrue(response.statusCode()>=400&&response.statusCode()<500,"Invalid amount "+amount+" returned "+response.statusCode());
            }
            for(long amount:new long[]{200,201,50000}){
                var response=client.send(HttpRequest.newBuilder(server.getURI().resolve(path+"/quote?amountMinor="+amount+"&feeCovered=true")).GET().build(),HttpResponse.BodyHandlers.ofString());
                assertEquals(200,response.statusCode());
                if(amount==50000)assertTrue(response.body().contains("\"totalAmountMinor\":51652"));
            }
        }
    }
    @Test void oldPoolAttemptIsAConflictAndNeverAnUpstreamGatewayFailure() throws Exception {
        var tx=new com.gratilo.payment.domain.TransactionEntity();tx.setId(UUID.randomUUID());tx.setProviderReference("cs_test_prior");
        var service=new StripeCheckoutService(null,null,null,"sk_test_fixture","http://localhost/success","http://localhost/cancel",new java.math.BigDecimal("1.5"),25,null) {
            @Override public Optional<CheckoutResponse> createPool(UUID user,TipPaymentRequest request){throw new CheckoutConflictException("CHECKOUT_ALREADY_PROCESSED",tx);}
        };
        var props=new HashMap<String,Object>();props.put("payments.enabled",true);props.put("features.tip-pools-enabled",true);props.put("stripe.secret-key","sk_test_fixture");props.put("stripe.webhook-secret","whsec_fixture");props.put("micronaut.server.port",-1);
        props.put("datasources.default.enabled",false);props.put("jpa.default.enabled",false);props.put("liquibase.enabled",false);props.put("liquibase.datasources.default.enabled",false);
        try(var context=ApplicationContext.builder().properties(props).environments("test").build()){
            context.registerSingleton(StripeCheckoutService.class,service);context.start();var server=context.getBean(EmbeddedServer.class).start();
            var response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(server.getURI().resolve("/payment-service/tips/pools/"+UUID.randomUUID()+"/checkout"))
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"tipAmountMinor\":500,\"idempotencyKey\":\"prior-attempt\"}")).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(409,response.statusCode());assertTrue(response.body().contains("CHECKOUT_ALREADY_PROCESSED"));assertTrue(response.body().contains("cs_test_prior"));
        }
    }

    @Test void unusableNetQuoteHasAnActionableBadRequestInsteadOfAGatewayError()throws Exception{
        var service=new StripeCheckoutService(null,null,null,"sk_test_fixture","http://localhost/success","http://localhost/cancel",new java.math.BigDecimal("99"),25,null) {
            @Override public com.gratilo.payment.api.TipQuote quote(UUID recipient,long amount,boolean covered) { return quote(amount,covered); }
        };
        var props=new HashMap<String,Object>();props.put("payments.enabled",true);props.put("stripe.fee-percent","99");props.put("stripe.secret-key","sk_test_fixture");props.put("stripe.webhook-secret","whsec_fixture");props.put("micronaut.server.port",-1);
        props.put("datasources.default.enabled",false);props.put("jpa.default.enabled",false);props.put("liquibase.enabled",false);props.put("liquibase.datasources.default.enabled",false);
        try(var context=ApplicationContext.builder().properties(props).environments("test").build()){
            context.registerSingleton(StripeCheckoutService.class,service);context.start();var server=context.getBean(EmbeddedServer.class).start();
            var result=HttpClient.newHttpClient().send(HttpRequest.newBuilder(server.getURI().resolve("/payment-service/tips/"+UUID.randomUUID()+"/quote?amountMinor=100&feeCovered=false")).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(400,result.statusCode());assertTrue(result.body().contains("TIP_TOO_SMALL_AFTER_FEES"));assertTrue(result.body().contains("0,01"));
        }
    }

    @Test void anonymousGuestCanUseCheckoutWithoutCustomerRegistrationOutsideTheAwsTestGate() throws Exception {
        var service = new StripeCheckoutService(null, null, null, "sk_test_fixture", "http://localhost/success", "http://localhost/cancel", new java.math.BigDecimal("1.5"), 25, null) {
            @Override public boolean canReceiveTips(UUID user) { return true; }
            @Override public com.gratilo.payment.api.TipQuote quote(UUID recipient,long amount,boolean covered) { return quote(amount,covered); }
            @Override public Optional<CheckoutResponse> create(UUID user, TipPaymentRequest request) {
                return Optional.of(new CheckoutResponse("https://checkout.stripe.com/test", UUID.randomUUID().toString(), false));
            }
        };
        var properties = new HashMap<String, Object>();
        properties.put("payments.enabled", true);
        properties.put("stripe.secret-key", "sk_test_fixture");
        properties.put("stripe.webhook-secret", "whsec_fixture");
        properties.put("micronaut.server.port", -1);
        properties.put("datasources.default.enabled", false);
        properties.put("jpa.default.enabled", false);
        properties.put("liquibase.enabled", false);
        properties.put("liquibase.datasources.default.enabled", false);
        try (var context = ApplicationContext.builder().properties(properties).environments("test").build()) {
            context.registerSingleton(StripeCheckoutService.class, service);
            context.start();
            var server = context.getBean(EmbeddedServer.class).start();
            var client = HttpClient.newHttpClient();
            var uri = server.getURI().resolve("/payment-service/tips/" + UUID.randomUUID());
            var response = client.send(HttpRequest.newBuilder(java.net.URI.create(uri + "/checkout"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"tipAmountMinor\":500,\"idempotencyKey\":\"guest-test\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("https://checkout.stripe.com/test"));
            var eligibility = client.send(HttpRequest.newBuilder(java.net.URI.create(uri + "/eligibility")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, eligibility.statusCode());
            assertTrue(eligibility.body().contains("true"));
            assertEquals("no-store", eligibility.headers().firstValue("Cache-Control").orElseThrow());
            var quote=client.send(HttpRequest.newBuilder(java.net.URI.create(uri+"/quote?amountMinor=2500&feeCovered=false")).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,quote.statusCode());assertTrue(quote.body().contains("\"recipientAmountMinor\":2312"));
            assertTrue(quote.body().contains("\"estimatedStripeFeeMinor\":63"));
        }
    }
}
