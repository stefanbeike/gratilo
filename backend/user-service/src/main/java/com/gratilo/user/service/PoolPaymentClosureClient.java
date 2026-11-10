package com.gratilo.user.service;
import io.micronaut.context.annotation.Value;
import io.micronaut.json.JsonMapper;
import jakarta.inject.Singleton;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
@Singleton public class PoolPaymentClosureClient {
    private final String base,key;
    public PoolPaymentClosureClient(@Value("${pool.payment-service-url:http://localhost:8082}") String base,@Value("${internal.api-key}") String key){this.base=base.replaceAll("/$","");this.key=key;}
    public boolean freeze(UUID id) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(base+"/payment-service/internal/pools/"+id+"/freeze")).header("X-Internal-Secret",key).timeout(Duration.ofSeconds(15)).POST(HttpRequest.BodyPublishers.noBody()).build();
        var response=HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString());
        if(response.statusCode()!=200)throw new IllegalStateException("PAYMENT_FREEZE_UNAVAILABLE");
        var result=JsonMapper.createDefault().readValue(response.body(),Map.class);
        if(!(result.get("safeToDissolve") instanceof Boolean safe))throw new IllegalStateException("INVALID_PAYMENT_FREEZE_RESPONSE");
        return safe;
    }
}
