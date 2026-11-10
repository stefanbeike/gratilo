package com.gratilo.user.service;

import io.micronaut.context.annotation.Value;
import io.micronaut.json.JsonMapper;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

@Singleton
public class PoolPaymentClosureClient {
    private final String base, key;
    public PoolPaymentClosureClient(@Value("${pool.payment-service-url:http://localhost:8082}") String base,
                                    @Value("${internal.api-key}") String key) {
        this.base=base.replaceAll("/$", ""); this.key=key;
    }
    public boolean freeze(UUID id) throws Exception {
        final HttpRequest request;
        try {
            request=HttpRequest.newBuilder(URI.create(base+"/payment-service/internal/pools/"+id+"/freeze"))
                    .header("X-Internal-Secret",key).header("Content-Type","application/json")
                    .timeout(Duration.ofSeconds(15)).POST(HttpRequest.BodyPublishers.noBody()).build();
        } catch (IllegalArgumentException invalid) {
            throw new FinanceCheckException("CONFIGURATION_INVALID",null);
        }
        final HttpResponse<String> response;
        try {
            response=HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException timeout) {
            throw new FinanceCheckException("TIMEOUT",null);
        } catch (IOException unavailable) {
            throw new FinanceCheckException("CONNECTION_FAILED",null);
        }
        if(response.statusCode()!=200) {
            String category="HTTP_ERROR";
            // Recognize only this fixed infrastructure code; never expose upstream response bodies.
            if(response.statusCode()==503) try {
                var error=JsonMapper.createDefault().readValue(response.body(),Map.class);
                if("PAYMENTS_DISABLED".equals(error.get("code")))category="PAYMENTS_DISABLED";
            } catch (IOException | RuntimeException ignored) { }
            throw new FinanceCheckException(category,response.statusCode());
        }
        try {
            var result=JsonMapper.createDefault().readValue(response.body(),Map.class);
            if(result.get("safeToDissolve") instanceof Boolean safe)return safe;
        } catch (IOException | RuntimeException invalid) {
            throw new FinanceCheckException("INVALID_RESPONSE",200);
        }
        throw new FinanceCheckException("INVALID_RESPONSE",200);
    }
    public static final class FinanceCheckException extends IOException {
        private final String category;
        private final Integer upstreamStatus;
        public FinanceCheckException(String category,Integer upstreamStatus) {
            super("Pool finance check: "+category+" status="+upstreamStatus);
            this.category=category;this.upstreamStatus=upstreamStatus;
        }
        public String category(){return category;}
        public Integer upstreamStatus(){return upstreamStatus;}
        public String explanation() {
            if("PAYMENTS_DISABLED".equals(category))return "Der Payment-Service blockiert die interne Finanzprüfung wegen deaktivierter Zahlungen. Der Payment-Service muss aktualisiert werden.";
            if(upstreamStatus!=null&&Set.of(401,403).contains(upstreamStatus))return "Die interne Authentifizierung zwischen User-Service und Payment-Service schlägt fehl (HTTP "+upstreamStatus+").";
            if(Integer.valueOf(404).equals(upstreamStatus))return "Der Endpunkt zur Pool-Auflösung fehlt im Payment-Service (HTTP 404). Bitte die bereitgestellte Version und Service-Adresse prüfen.";
            if(upstreamStatus!=null&&upstreamStatus!=200)return "Der Payment-Service kann die Finanzprüfung nicht abschließen (HTTP "+upstreamStatus+").";
            return switch(category) {
                case "TIMEOUT" -> "Der Payment-Service antwortet nicht rechtzeitig auf die Finanzprüfung.";
                case "CONNECTION_FAILED" -> "Der Payment-Service ist unter der konfigurierten internen Adresse nicht erreichbar.";
                case "CONFIGURATION_INVALID" -> "Die interne Verbindung zum Payment-Service ist ungültig konfiguriert.";
                default -> "Der Payment-Service liefert keine gültige Antwort auf die Finanzprüfung.";
            };
        }
    }
}
