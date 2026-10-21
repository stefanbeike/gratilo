package com.gratilo.payment.service;

import com.gratilo.payment.repository.ConnectedAccountRepository;
import io.micronaut.context.annotation.Value;
import io.micronaut.json.JsonMapper;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.inject.Singleton;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;

/** Connected-account GET only. Neither the request URL nor the caller selects a Stripe account. */
@Singleton
public class StripeBalanceService {
    private final ConnectedAccountRepository accounts;private final String secret;
    public StripeBalanceService(ConnectedAccountRepository accounts,@Value("${stripe.secret-key:}") String secret){this.accounts=accounts;StripeConnectService.requireTestKey(secret);this.secret=secret;}
    @Serdeable public record Balance(long availableMinor,long pendingMinor,String currency,OffsetDateTime checkedAt){}
    public Optional<Balance> balance(UUID user)throws Exception{
        var account=accounts.findByUserId(user).orElse(null);
        if(account==null||account.getStripeAccountId()==null)return Optional.empty();
        String id=account.getStripeAccountId();
        if(secret.isBlank()||!id.matches("acct_[A-Za-z0-9]+")||id.startsWith("acct_demo_"))throw new IllegalStateException("Balance unavailable");
        var data=JsonMapper.createDefault().readValue(get(id),Map.class);
        if(!Boolean.FALSE.equals(data.get("livemode")))throw new IllegalStateException("Sandbox balance required");
        return Optional.of(new Balance(amount(data.get("available")),amount(data.get("pending")),"EUR",OffsetDateTime.now()));
    }
    private static long amount(Object value){
        if(!(value instanceof List<?> entries))throw new IllegalStateException("Missing balance section");
        long result=0;boolean found=false;
        for(Object entry:entries){
            if(!(entry instanceof Map<?,?> row)||!(row.get("currency") instanceof String))throw new IllegalStateException("Invalid balance entry");
            if(!"eur".equals(row.get("currency")))continue;
            if(found||!(row.get("amount") instanceof Number n))throw new IllegalStateException("Ambiguous balance");
            result=new java.math.BigDecimal(n.toString()).longValueExact();found=true;
        }
        return result;
    }
    protected String get(String account)throws Exception{
        var request=HttpRequest.newBuilder(URI.create("https://api.stripe.com/v1/balance")).timeout(Duration.ofSeconds(10))
            .header("Authorization","Bearer "+secret).header("Stripe-Account",account).header("Stripe-Version","2026-09-30.endive").GET().build();
        var response=HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString());
        if(response.statusCode()!=200)throw new IllegalStateException("Stripe balance unavailable");return response.body();
    }
}
