package com.gratilo.payment.service;

import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.server.EmbeddedServer;
import org.junit.jupiter.api.Test;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import static org.junit.jupiter.api.Assertions.*;

class StripePaymentAuditAdminHttpTest {
    @Test void adminOnlyLookupDoesNotExposeHistoricalOrOtherModeData()throws Exception {
        var f=new StripePaymentAuditorTest.Fixture(false,2);f.reconcile();
        int stripeReads=f.client.reads;
        String jwtSecret="audit-http-fixture-"+"z".repeat(64);
        var props=new HashMap<String,Object>();props.put("micronaut.server.port",-1);props.put("payments.enabled",true);props.put("payments.test-user-ids",UUID.randomUUID().toString());props.put("stripe.secret-key","sk_test_fixture");props.put("stripe.webhook-secret","whsec_fixture");props.put("internal.api-key","audit-http-fixture");props.put("micronaut.security.token.jwt.signatures.secret.validation.secret",jwtSecret);
        props.put("datasources.default.enabled",false);props.put("jpa.default.enabled",false);props.put("liquibase.enabled",false);props.put("liquibase.datasources.default.enabled",false);props.put("notifications.tip-payments-enabled",false);
        var offlineClient=new StripePaymentAuditClient("sk_test_fixture"){@Override public boolean configured(){return false;}@Override public Map<String,Object> get(String p,String a){throw new AssertionError("Admin reads must not invoke Stripe");}};
        try(var context=ApplicationContext.builder().properties(props).environments("test","prd").build()){
            context.registerSingleton(StripePaymentAuditStore.class,f.store);context.registerSingleton(StripePaymentAuditClient.class,offlineClient);context.registerSingleton(StripePaymentAuditor.class,f.service);context.start();
            var server=context.getBean(EmbeddedServer.class).start();var client=HttpClient.newHttpClient();var base=server.getURI();String route="/payment-service/admin/stripe-payment-audits";
            assertEquals(401,client.send(HttpRequest.newBuilder(base.resolve(route+"?reference=pi_audit&livemode=false")).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode());
            assertEquals(403,get(client,base.resolve(route+"?reference=pi_audit&livemode=false"),token(jwtSecret,false)).statusCode());
            var response=get(client,base.resolve(route+"?reference=tr_audit1&livemode=false"),token(jwtSecret,true));assertEquals(200,response.statusCode(),response.body());assertTrue(response.body().contains("txn_recipient1"));assertTrue(response.body().contains("acct_recipient1"));assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());
            assertEquals("[]",get(client,base.resolve(route+"?reference=ch_audit&livemode=true"),token(jwtSecret,true)).body());
            assertEquals(404,get(client,base.resolve(route+"/"+UUID.randomUUID()+"?livemode=false"),token(jwtSecret,true)).statusCode());
            assertEquals(400,get(client,base.resolve(route+"?reference=pi_audit"),token(jwtSecret,true)).statusCode());
            var events=get(client,base.resolve(route+"/"+f.tx.getId()+"/events?livemode=false&limit=1"),token(jwtSecret,true));assertEquals(200,events.statusCode(),events.body());assertTrue(events.body().contains("nextAfter"));assertEquals(stripeReads,f.client.reads);
        }
    }
    static HttpResponse<String> get(HttpClient c,java.net.URI uri,String token)throws Exception{return c.send(HttpRequest.newBuilder(uri).header("Authorization","Bearer "+token).GET().build(),HttpResponse.BodyHandlers.ofString());}
    static String token(String secret,boolean admin)throws Exception{
        var encode=Base64.getUrlEncoder().withoutPadding();String header=encode.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));String body=encode.encodeToString(("{\"sub\":\""+UUID.randomUUID()+"\",\"exp\":"+(System.currentTimeMillis()/1000+120)+",\"roles\":[\""+(admin?"ROLE_ADMIN":"ROLE_USER")+"\"]}").getBytes(StandardCharsets.UTF_8));String raw=header+"."+body;var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return raw+"."+encode.encodeToString(mac.doFinal(raw.getBytes(StandardCharsets.UTF_8)));
    }
}
