package com.gratilo.payment.controller;

import com.gratilo.payment.api.*;
import com.gratilo.payment.service.StripePayoutService;
import io.micronaut.http.*;
import io.micronaut.http.annotation.*;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.security.annotation.Secured;
import java.util.*;

@Controller("/payment-service/payouts")
@Secured(SecurityRule.IS_AUTHENTICATED)
@io.micronaut.scheduling.annotation.ExecuteOn(io.micronaut.scheduling.TaskExecutors.BLOCKING)
public class PayoutController {
    private final StripePayoutService payouts;
    public PayoutController(StripePayoutService payouts) { this.payouts=payouts; }
    @Get public HttpResponse<?> history(Authentication auth) {return HttpResponse.ok(payouts.history(user(auth)).stream().map(TransactionResponse::from).toList()).header("Cache-Control","no-store");}
    @Get("/eligibility") public HttpResponse<?> eligibility(Authentication auth) {
        try {return HttpResponse.ok(payouts.eligibility(user(auth))).header("Cache-Control","no-store");}
        catch(StripePayoutService.SetupException setup){return HttpResponse.status(HttpStatus.CONFLICT).body(setup.result).header("Cache-Control","no-store");}
        catch(IllegalStateException invalid){return error(HttpStatus.CONFLICT,invalid.getMessage());}
        catch(Exception unavailable){return error(HttpStatus.BAD_GATEWAY,"Die Auszahlungsvoraussetzungen konnten nicht zuverlässig geprüft werden.");}
    }
    @Post("/setup") public HttpResponse<?> setup(Authentication auth) {
        return HttpResponse.ok(payouts.prepareAccount(user(auth))).header("Cache-Control","no-store");
    }
    @Post public HttpResponse<?> create(Authentication auth,@Body @Nullable PayoutRequest request) {
        if(request==null||request.requestId()==null)return error(HttpStatus.BAD_REQUEST,"Eine eindeutige Auszahlungs-ID ist erforderlich.");
        try {return HttpResponse.ok(TransactionResponse.from(payouts.create(user(auth),request.amountMinor(),request.requestId()))).header("Cache-Control","no-store");}
        catch(StripePayoutService.SetupException setup){return HttpResponse.status(HttpStatus.CONFLICT).body(setup.result).header("Cache-Control","no-store");}
        catch(IllegalArgumentException invalid){return error(HttpStatus.BAD_REQUEST,invalid.getMessage());}
        catch(IllegalStateException invalid){return error(HttpStatus.CONFLICT,invalid.getMessage());}
        catch(Exception unavailable){return error(HttpStatus.BAD_GATEWAY,"Auszahlungsergebnis nicht verfügbar. Bitte dieselbe Anfrage erneut laden oder den Status abgleichen.");}
    }
    @Post("/{id}/reconcile") public HttpResponse<?> reconcile(Authentication auth,UUID id) {
        try {return HttpResponse.ok(TransactionResponse.from(payouts.reconcile(user(auth),id))).header("Cache-Control","no-store");}
        catch(IllegalArgumentException absent){return error(HttpStatus.NOT_FOUND,"Auszahlung nicht gefunden.");}
        catch(Exception unavailable){return error(HttpStatus.BAD_GATEWAY,"Der Auszahlungsstatus konnte nicht abgeglichen werden. Es wird keine neue Auszahlung erstellt.");}
    }
    private static UUID user(Authentication auth){return UUID.fromString(auth.getName());}
    private static HttpResponse<?> error(HttpStatus status,String message){return HttpResponse.status(status).body(Map.of("message",message));}
}
