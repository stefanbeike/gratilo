package com.gratilo.payment.service;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RecipientAvailabilityTest {
    @Test void manualPersonalDeactivationStillBlocksPersonalCheckout()throws Exception {
        UUID id=UUID.randomUUID();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/user-service/users/public/",exchange->{
            byte[] body=("{\"userId\":\""+id+"\",\"paymentEnabled\":false,\"personalLinkPaused\":false,\"poolParticipationEnabled\":true}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });
        server.start();
        try {
            var availability=new RecipientAvailability("http://127.0.0.1:"+server.getAddress().getPort());
            assertThrows(IllegalStateException.class,()->availability.requireEnabled(id));
            assertDoesNotThrow(()->availability.requirePoolMemberEnabled(id));
        }finally{server.stop(0);}
    }
    @Test void ownerMayReceivePersonalTipsAndPoolTransfersAtTheSameTime()throws Exception {
        UUID id=UUID.randomUUID();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/user-service/users/public/",exchange->{
            byte[] body=("{\"userId\":\""+id+"\",\"paymentEnabled\":true,\"personalLinkPaused\":false,\"poolParticipationEnabled\":true}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });server.start();
        try {var availability=new RecipientAvailability("http://127.0.0.1:"+server.getAddress().getPort());
            assertDoesNotThrow(()->availability.requireEnabled(id));assertDoesNotThrow(()->availability.requirePoolMemberEnabled(id));
        }finally{server.stop(0);}
    }
}
