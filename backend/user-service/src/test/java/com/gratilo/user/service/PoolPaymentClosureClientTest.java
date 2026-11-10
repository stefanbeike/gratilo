package com.gratilo.user.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class PoolPaymentClosureClientTest {
    @Test void readsThePaymentServiceEmptyPoolDecisionAndSendsTheInternalCredentials()throws Exception{
        try(var f=new Fixture()){
            assertTrue(f.client.freeze(f.pool));
            assertEquals("POST",f.method.get());assertEquals("fixture-key",f.key.get());assertEquals("application/json",f.type.get());
            assertEquals("/payment-service/internal/pools/"+f.pool+"/freeze",f.path.get());
            f.body.set("{\"safeToDissolve\":false}");assertFalse(f.client.freeze(f.pool));
        }
    }
    @Test void exposesSafeHttpDiagnosticsWithoutLeakingResponseBodies()throws Exception{
        try(var f=new Fixture()){
            f.body.set("{\"message\":\"upstream-secret-sentinel\"}");
            for(int status:new int[]{401,403,404,415,500,503}){
                f.status.set(status);
                var error=assertThrows(PoolPaymentClosureClient.FinanceCheckException.class,()->f.client.freeze(f.pool));
                assertEquals(status,error.upstreamStatus());assertEquals("HTTP_ERROR",error.category());
                assertFalse(error.getMessage().contains("upstream-secret-sentinel"));assertFalse(error.explanation().contains("upstream-secret-sentinel"));
                assertTrue(error.explanation().contains("HTTP "+status));
            }
        }
    }
    @Test void identifiesAnOldPaymentFilterWhichBlocksTheFinanceCheck()throws Exception{
        try(var f=new Fixture()){
            f.status.set(503);f.body.set("{\"code\":\"PAYMENTS_DISABLED\"}");
            var error=assertThrows(PoolPaymentClosureClient.FinanceCheckException.class,()->f.client.freeze(f.pool));
            assertEquals("PAYMENTS_DISABLED",error.category());assertTrue(error.explanation().contains("aktualisiert"));
        }
    }
    @Test void malformedOrIncompleteRepliesNeverAuthorizeDissolution()throws Exception{
        try(var f=new Fixture()){
            for(String body:new String[]{"{}","{\"safeToDissolve\":\"true\"}","null","not-json"}){
                f.body.set(body);
                var error=assertThrows(PoolPaymentClosureClient.FinanceCheckException.class,()->f.client.freeze(f.pool));
                assertEquals("INVALID_RESPONSE",error.category());
            }
        }
    }
    private static final class Fixture implements AutoCloseable {
        final UUID pool=UUID.randomUUID();final AtomicInteger status=new AtomicInteger(200);
        final AtomicReference<String> body=new AtomicReference<>("{\"safeToDissolve\":true}");
        final AtomicReference<String> method=new AtomicReference<>(),key=new AtomicReference<>(),type=new AtomicReference<>(),path=new AtomicReference<>();
        final HttpServer server;final PoolPaymentClosureClient client;
        Fixture()throws Exception{
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/payment-service/internal/pools/",exchange->{
                method.set(exchange.getRequestMethod());key.set(exchange.getRequestHeaders().getFirst("X-Internal-Secret"));
                type.set(exchange.getRequestHeaders().getFirst("Content-Type"));path.set(exchange.getRequestURI().getPath());
                byte[] bytes=body.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(status.get(),bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
            });server.start();
            client=new PoolPaymentClosureClient("http://127.0.0.1:"+server.getAddress().getPort()+"/","fixture-key");
        }
        public void close(){server.stop(0);}
    }
}
