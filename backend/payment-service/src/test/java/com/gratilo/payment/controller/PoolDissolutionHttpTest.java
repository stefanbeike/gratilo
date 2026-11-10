package com.gratilo.payment.controller;

import com.gratilo.payment.service.PoolCheckoutGate;
import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.server.EmbeddedServer;
import org.junit.jupiter.api.Test;
import java.net.http.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PoolDissolutionHttpTest {
    @Test void internalEmptyPoolCheckWorksOverHttpInProductionProfile()throws Exception{ verifyFreeze(true); }
    @Test void emptyPoolCanBeClosedWhilePaymentsAreDisabled()throws Exception{ verifyFreeze(false); }
    private void verifyFreeze(boolean enabled)throws Exception{
        var props=new HashMap<String,Object>();
        props.put("payments.enabled",enabled);props.put("payments.test-user-ids",UUID.randomUUID().toString());props.put("features.tip-pools-enabled",true);
        props.put("stripe.webhook-secret","whsec_fixture");props.put("stripe.secret-key","sk_test_fixture");props.put("internal.api-key","internal-finance-test");
        props.put("micronaut.security.token.jwt.signatures.secret.validation.secret","private-jwt-test-key-at-least-thirty-two-characters");
        props.put("micronaut.server.port",-1);props.put("notifications.tip-payments-enabled",false);
        props.put("datasources.default.enabled",false);props.put("jpa.default.enabled",false);
        props.put("liquibase.enabled",false);props.put("liquibase.datasources.default.enabled",false);
        var gate=mock(PoolCheckoutGate.class);UUID pool=UUID.randomUUID();when(gate.freeze(pool)).thenReturn(true);
        try(var context=ApplicationContext.builder().properties(props).environments("test","prd").build()){
            context.registerSingleton(PoolCheckoutGate.class,gate);context.start();
            var server=context.getBean(EmbeddedServer.class).start();var client=HttpClient.newHttpClient();
            var uri=server.getURI().resolve("/payment-service/internal/pools/"+pool+"/freeze");
            var response=client.send(HttpRequest.newBuilder(uri).header("X-Internal-Secret","internal-finance-test").POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,response.statusCode(),response.body());assertTrue(response.body().contains("\"safeToDissolve\":true"));
            var denied=client.send(HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(401,denied.statusCode());
            var wrong=client.send(HttpRequest.newBuilder(uri).header("X-Internal-Secret","wrong-key").POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(401,wrong.statusCode());verify(gate,times(1)).freeze(pool);
            when(gate.freeze(pool)).thenReturn(false);
            var blocked=client.send(HttpRequest.newBuilder(uri).header("X-Internal-Secret","internal-finance-test").POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,blocked.statusCode());assertTrue(blocked.body().contains("\"safeToDissolve\":false"));
            if(!enabled){
                var checkout=client.send(HttpRequest.newBuilder(server.getURI().resolve("/payment-service/tips/pools/"+pool+"/checkout")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),HttpResponse.BodyHandlers.ofString());
                assertEquals(503,checkout.statusCode());assertTrue(checkout.body().contains("PAYMENTS_DISABLED"));
            }
        }
    }
}
