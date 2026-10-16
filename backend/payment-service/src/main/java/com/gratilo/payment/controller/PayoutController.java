package com.gratilo.payment.controller;

import com.gratilo.payment.api.TransactionResponse;
import com.gratilo.payment.api.PayoutRequest;
import com.gratilo.payment.service.StripePayoutService;
import io.micronaut.http.*;
import io.micronaut.http.annotation.*;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.security.annotation.Secured;
import java.util.UUID;

@Controller("/payment-service/payouts")
@Secured(SecurityRule.IS_AUTHENTICATED)
public class PayoutController {
    private final StripePayoutService payouts;
    public PayoutController(StripePayoutService payouts) { this.payouts = payouts; }
    @Post public HttpResponse<?> create(Authentication authentication, @Body @Nullable PayoutRequest request) {
        try { return HttpResponse.ok(TransactionResponse.from(payouts.create(UUID.fromString(authentication.getName()), request == null ? null : request.amountMinor()))); }
        catch (Exception e) { return HttpResponse.badRequest(java.util.Map.of("message", e.getMessage() == null ? "Auszahlung konnte nicht erstellt werden." : e.getMessage())); }
    }
}
