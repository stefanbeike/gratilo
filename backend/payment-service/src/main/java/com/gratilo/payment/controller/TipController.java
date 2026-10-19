package com.gratilo.payment.controller;
import com.gratilo.payment.api.*;
import com.gratilo.payment.service.TipPricing;
import io.micronaut.http.*;
import io.micronaut.http.annotation.*;
import jakarta.validation.Valid;
import java.util.UUID;
import com.gratilo.payment.service.StripeCheckoutService;
import io.micronaut.context.annotation.Value;
@Controller("/payment-service/tips")
@io.micronaut.scheduling.annotation.ExecuteOn(io.micronaut.scheduling.TaskExecutors.BLOCKING)
public class TipController {
    private final StripeCheckoutService checkoutService;
    private final double feePercent;
    private final long fixedFeeMinor;
    public TipController(StripeCheckoutService checkoutService, @Value("${stripe.fee-percent:1.5}") double feePercent,
                         @Value("${stripe.fee-fixed-minor:25}") long fixedFeeMinor) {
        this.checkoutService = checkoutService; this.feePercent = feePercent; this.fixedFeeMinor = fixedFeeMinor;
    }
    @Get("/{recipientUserId}/quote") public TipQuote quote(UUID recipientUserId, @QueryValue @jakarta.validation.constraints.Min(100) @jakarta.validation.constraints.Max(1000000) long amountMinor, @QueryValue(value = "feeCovered", defaultValue = "false") boolean feeCovered) {
        long fee = feeCovered ? TipPricing.serviceFeeMinor(amountMinor, feePercent, fixedFeeMinor) : 0; return new TipQuote(amountMinor, fee, amountMinor + fee, "EUR", (int) Math.round(feePercent), fixedFeeMinor);
    }
    @Get("/checkout/confirm") public HttpResponse<?> confirm(@QueryValue String sessionId) {
        try { return HttpResponse.ok(java.util.Map.of("confirmed", checkoutService.confirmSession(sessionId))); }
        catch (Exception e) { return HttpResponse.status(HttpStatus.BAD_GATEWAY).body(java.util.Map.of("confirmed", false)); }
    }
    @Post("/{recipientUserId}/checkout") public HttpResponse<?> checkout(UUID recipientUserId, @QueryValue(value = "demo", defaultValue = "false") String demo, @Body @Valid TipPaymentRequest request) {
        if (request.tipAmountMinor() < 100) return HttpResponse.badRequest(new com.gratilo.payment.api.TransactionResponse(null, recipientUserId, 0, "EUR", null, null, null, "Mindesttrinkgeld: 1,00 EUR", "DEMO", null, "card", null, null, null, null, null, "{}"));
        boolean demoRequested = "1".equals(demo) || "true".equalsIgnoreCase(demo);
        if (demoRequested) return HttpResponse.badRequest(java.util.Map.of("message", "Demo-Zahlungen sind deaktiviert."));
        try {
            var checkout = checkoutService.create(recipientUserId, request);
            if (checkout.isPresent()) return HttpResponse.ok(checkout.get());
            return HttpResponse.status(HttpStatus.SERVICE_UNAVAILABLE).body(java.util.Map.of("message", "Stripe Checkout ist noch nicht verfügbar."));
        } catch (Exception exception) { return HttpResponse.status(HttpStatus.BAD_GATEWAY).body(java.util.Map.of("message", "Stripe Checkout konnte nicht erstellt werden.")); }
    }
}
