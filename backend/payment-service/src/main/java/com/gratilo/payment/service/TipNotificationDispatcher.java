package com.gratilo.payment.service;

import io.micronaut.context.annotation.*;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import java.net.*;
import java.net.http.*;
import java.time.Duration;

@Singleton
@Requires(property="notifications.tip-payments-enabled",value="true")
public class TipNotificationDispatcher {
    private final TipNotificationDelivery delivery;
    private final URI endpoint;
    private final String key;
    public TipNotificationDispatcher(TipNotificationDelivery delivery,@Value("${pool.user-service-url:http://localhost:8081}") String base,@Value("${internal.api-key}") String key){
        this.delivery=delivery;this.endpoint=URI.create(base.replaceAll("/$","")+"/user-service/internal/notifications/tip-payments");this.key=key;
    }
    @Scheduled(fixedDelay="PT15S") public void dispatch(){
        for(int i=0;i<20;i++){
            var job=delivery.claim();if(job.isEmpty())return;int status=0;
            try{status=send(job.get().payload());}catch(Exception failure){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Tip notification handoff failed key={} category={}",job.get().key(),failure.getClass().getSimpleName());}
            delivery.finish(job.get(),status);
        }
    }
    protected int send(String payload)throws Exception{
        var request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(20)).header("Content-Type","application/json").header("X-Internal-Secret",key).POST(HttpRequest.BodyPublishers.ofString(payload)).build();
        return HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
