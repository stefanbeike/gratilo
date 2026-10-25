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
    @Test void distinguishesConfirmedDisabledMemberFromUnavailableUserService()throws Exception {
        UUID id=UUID.randomUUID();var status=new java.util.concurrent.atomic.AtomicInteger(503);
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/user-service/users/public/",exchange->{
            int code=status.get();byte[] body;
            if(code==200)body=("{\"userId\":\""+id+"\",\"poolParticipationEnabled\":false}").getBytes(StandardCharsets.UTF_8);
            else body="{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(code,body.length);exchange.getResponseBody().write(body);exchange.close();
        });server.start();
        try {
            var availability=new RecipientAvailability("http://127.0.0.1:"+server.getAddress().getPort());
            assertThrows(IllegalStateException.class,()->availability.requirePoolMemberEnabled(id),"5xx must remain an unknown technical failure");
            status.set(200);assertThrows(RecipientAvailability.NotEnabledException.class,()->availability.requirePoolMemberEnabled(id),"a valid profile explicitly disabling participation is a confirmed block");
            status.set(404);assertThrows(RecipientAvailability.NotEnabledException.class,()->availability.requirePoolMemberEnabled(id),"a missing recipient is a confirmed block");
        }finally{server.stop(0);}
    }
}
