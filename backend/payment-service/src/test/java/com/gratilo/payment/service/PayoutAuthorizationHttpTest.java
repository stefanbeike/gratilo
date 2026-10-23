package com.gratilo.payment.service;

import com.gratilo.payment.domain.*;
import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.server.EmbeddedServer;
import org.junit.jupiter.api.Test;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import static org.junit.jupiter.api.Assertions.*;

class PayoutAuthorizationHttpTest {
    @Test void anonymousRequestsAndForeignPayoutIdsCannotCreateOrReadAPayout()throws Exception {
        UUID owner=UUID.randomUUID(),other=UUID.randomUUID(),id=UUID.randomUUID();
        var tx=new TransactionEntity();tx.setId(id);tx.setAccountUserId(owner);tx.setAmountMinor(-2500);tx.setType(TransactionType.PAYOUT);tx.setStatus(TransactionStatus.PENDING);tx.setProvider("STRIPE_SANDBOX");
        var store=new ManualPayoutTest.Store();store.values.put(id,tx);
        UUID[] caller={null};
        var service=new StripePayoutService(store,null,null,null,"sk_test_fixture",true,false,""){
            @Override public StripePayoutSetup.Result prepareAccount(UUID user){caller[0]=user;return new StripePayoutSetup.Result("READY",true,true,"manual","","Ready","",java.time.OffsetDateTime.now());}
            @Override public TransactionEntity create(UUID user,Long amount,UUID request){caller[0]=user;assertEquals(2500L,amount);return tx;}
            @Override public List<TransactionEntity> history(UUID user){return user.equals(owner)?List.of(tx):List.of();}
            @Override public TransactionEntity reconcile(UUID user,UUID payout){if(!owner.equals(user)||!id.equals(payout))throw new IllegalArgumentException();return tx;}
        };
        String secret="http-fixture-"+"s".repeat(64);
        var properties=new HashMap<String,Object>();properties.put("micronaut.server.port",-1);properties.put("payments.enabled",true);properties.put("stripe.secret-key","sk_test_fixture");properties.put("stripe.webhook-secret","whsec_fixture");properties.put("internal.api-key","fixture-only");properties.put("micronaut.security.token.jwt.signatures.secret.validation.secret",secret);
        properties.put("datasources.default.enabled",false);properties.put("jpa.default.enabled",false);properties.put("liquibase.enabled",false);properties.put("liquibase.datasources.default.enabled",false);properties.put("notifications.tip-payments-enabled",false);
        try(var context=ApplicationContext.builder().properties(properties).environments("test").build()){
            context.registerSingleton(StripePayoutService.class,service);context.start();var server=context.getBean(EmbeddedServer.class).start();var base=server.getURI();var client=HttpClient.newHttpClient();
            String body="{\"amountMinor\":2500,\"requestId\":\""+UUID.randomUUID()+"\",\"stripeAccountId\":\"acct_foreign\",\"userId\":\""+other+"\"}";
            for(String path:List.of("/payment-service/payouts","/payment-service/payouts/eligibility")){
                int status=client.send(HttpRequest.newBuilder(base.resolve(path)).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode();assertTrue(status==401||status==403);
            }
            assertEquals(401,client.send(HttpRequest.newBuilder(base.resolve("/payment-service/payouts")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.discarding()).statusCode());assertNull(caller[0]);
            assertEquals(401,client.send(HttpRequest.newBuilder(base.resolve("/payment-service/payouts/setup")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.discarding()).statusCode());assertNull(caller[0]);
            var setup=client.send(HttpRequest.newBuilder(base.resolve("/payment-service/payouts/setup")).header("Authorization","Bearer "+jwt(owner,secret)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,setup.statusCode(),setup.body());assertEquals(owner,caller[0]);
            var created=client.send(HttpRequest.newBuilder(base.resolve("/payment-service/payouts")).header("Authorization","Bearer "+jwt(owner,secret)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,created.statusCode(),created.body());assertEquals(owner,caller[0]);
            var foreign=client.send(HttpRequest.newBuilder(base.resolve("/payment-service/payouts/"+id+"/reconcile")).header("Authorization","Bearer "+jwt(other,secret)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),HttpResponse.BodyHandlers.ofString());assertEquals(404,foreign.statusCode());assertFalse(foreign.body().contains(owner.toString()));
            var history=client.send(HttpRequest.newBuilder(base.resolve("/payment-service/payouts")).header("Authorization","Bearer "+jwt(other,secret)).GET().build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,history.statusCode());assertEquals("[]",history.body());
        }
    }
    private static String jwt(UUID user,String secret)throws Exception {
        var encoder=Base64.getUrlEncoder().withoutPadding();String raw=encoder.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8))+"."+encoder.encodeToString(("{\"sub\":\""+user+"\",\"exp\":"+(System.currentTimeMillis()/1000+120)+"}").getBytes(StandardCharsets.UTF_8));
        var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return raw+"."+encoder.encodeToString(mac.doFinal(raw.getBytes(StandardCharsets.UTF_8)));
    }
}
