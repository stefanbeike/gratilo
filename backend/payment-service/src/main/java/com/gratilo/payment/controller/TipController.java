package com.gratilo.payment.controller;
import com.gratilo.payment.api.*;
import com.gratilo.payment.service.TipPricing;
import io.micronaut.http.*;
import io.micronaut.http.annotation.*;
import jakarta.validation.Valid;
import java.util.UUID;
import java.math.BigDecimal;
import com.gratilo.payment.service.StripeCheckoutService;
import io.micronaut.context.annotation.Value;
@Controller("/payment-service/tips")
@io.micronaut.scheduling.annotation.ExecuteOn(io.micronaut.scheduling.TaskExecutors.BLOCKING)
public class TipController {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(TipController.class);
    private final StripeCheckoutService checkoutService;
    private final BigDecimal feePercent;
    private final long fixedFeeMinor;
    public TipController(StripeCheckoutService checkoutService, @Value("${stripe.fee-percent:3.15}") BigDecimal feePercent,
                         @Value("${stripe.fee-fixed-minor:25}") long fixedFeeMinor) {
        this.checkoutService = checkoutService; this.feePercent = feePercent; this.fixedFeeMinor = fixedFeeMinor;
    }
    @Get("/{recipientUserId}/eligibility") public HttpResponse<?> eligibility(UUID recipientUserId) {
        return HttpResponse.ok(java.util.Map.of("canReceiveTips", checkoutService.canReceiveTips(recipientUserId)))
                .header("Cache-Control", "no-store");
    }
    @io.micronaut.security.annotation.Secured(io.micronaut.security.rules.SecurityRule.IS_ANONYMOUS)
    @Get("/pools/{ownerUserId}/eligibility") public HttpResponse<?> poolEligibility(UUID ownerUserId) {
        try {
            return HttpResponse.ok(java.util.Map.of("canReceiveTips", checkoutService.checkPoolEligibility(ownerUserId)))
                    .header("Cache-Control", "no-store");
        } catch (Exception unavailable) {
            LOG.warn("Pool eligibility check unavailable: {}", unavailable.getClass().getSimpleName());
            return HttpResponse.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(java.util.Map.of("canReceiveTips", false, "status", "CHECK_FAILED",
                            "message", "Der Zahlungsstatus wird derzeit geprüft. Bitte versuche es erneut."))
                    .header("Cache-Control", "no-store");
        }
    }
    @Get("/{recipientUserId}/quote") public TipQuote quote(UUID recipientUserId, @QueryValue @jakarta.validation.constraints.Min(100) @jakarta.validation.constraints.Max(1000000) long amountMinor, @QueryValue(value = "feeCovered", defaultValue = "false") boolean feeCovered) throws Exception {
        return checkoutService==null?TipPricing.quote(amountMinor,feeCovered,feePercent,fixedFeeMinor):checkoutService.quote(recipientUserId,amountMinor,feeCovered);
    }
    @Get("/pools/{ownerUserId}/quote") public TipQuote poolQuote(UUID ownerUserId, @QueryValue @jakarta.validation.constraints.Min(100) @jakarta.validation.constraints.Max(1000000) long amountMinor, @QueryValue(value = "feeCovered", defaultValue = "false") boolean feeCovered) {
        if(checkoutService==null)new com.gratilo.payment.service.PoolPaymentLimits(200,50000).validate(amountMinor);
        return checkoutService==null?TipPricing.poolQuote(amountMinor,feeCovered,feePercent,fixedFeeMinor,TipPricing.DEFAULT_PLATFORM_PERCENT):checkoutService.poolQuote(amountMinor,feeCovered);
    }
    @io.micronaut.security.annotation.Secured(io.micronaut.security.rules.SecurityRule.IS_ANONYMOUS)
    @Get("/pool-limits") public HttpResponse<?> poolLimits(){
        return HttpResponse.ok(checkoutService.poolLimits()).header("Cache-Control","no-store");
    }
    @Get("/checkout/confirm") public HttpResponse<?> confirm(@QueryValue String sessionId) {
        try { return HttpResponse.ok(checkoutService.checkoutStatus(sessionId)).header("Cache-Control", "no-store"); }
        catch (Exception e) { return HttpResponse.ok(java.util.Map.of("status", "PENDING", "confirmed", false)).header("Cache-Control", "no-store"); }
    }
    @Post("/{recipientUserId}/checkout") public HttpResponse<?> checkout(UUID recipientUserId, @QueryValue(value = "demo", defaultValue = "false") String demo, @Body @Valid TipPaymentRequest request) {
        if (request.tipAmountMinor() < 100) return HttpResponse.badRequest(new com.gratilo.payment.api.TransactionResponse(null, recipientUserId, 0, "EUR", null, null, null, "Mindesttrinkgeld: 1,00 EUR", "DEMO", null, "card", null, null, null, null, null, "{}"));
        boolean demoRequested = "1".equals(demo) || "true".equalsIgnoreCase(demo);
        if (demoRequested) return HttpResponse.badRequest(java.util.Map.of("message", "Demo-Zahlungen sind deaktiviert."));
        try {
            var checkout = checkoutService.create(recipientUserId, request);
            if (checkout.isPresent()) return HttpResponse.ok(checkout.get());
            return HttpResponse.status(HttpStatus.SERVICE_UNAVAILABLE).body(java.util.Map.of("message", "Stripe Checkout ist noch nicht verfügbar."));
        } catch (StripeCheckoutService.PricingNotReleasedException blocked) {
            return HttpResponse.status(HttpStatus.SERVICE_UNAVAILABLE).body(java.util.Map.of("code","PRICING_NOT_RELEASED","message",blocked.getMessage()));
        } catch (TipPricing.NoDistributableTipException small) {
            return tipTooSmall(small);
        } catch (StripeCheckoutService.InvalidCheckoutRequestException invalid) {
            return HttpResponse.badRequest(java.util.Map.of("code","INVALID_CHECKOUT_REQUEST","message",invalid.getMessage()));
        } catch (StripeCheckoutService.CheckoutConflictException conflict) {
            return HttpResponse.status(HttpStatus.CONFLICT).body(conflict.response());
        } catch (StripeCheckoutService.QuoteChangedException changed) {
            return HttpResponse.status(HttpStatus.CONFLICT).body(java.util.Map.of("code","QUOTE_CHANGED","message",changed.getMessage()));
        } catch (StripeCheckoutService.RecipientNotReadyException unavailable) {
            return HttpResponse.status(HttpStatus.CONFLICT).body(java.util.Map.of("code", "RECIPIENT_NOT_READY", "message", "Dieser Empfänger kann derzeit keine Trinkgelder empfangen."));
        } catch (Exception exception) {
            // Never log request bodies, Stripe keys, account IDs, or Stripe error bodies.
            if (exception instanceof StripeCheckoutService.StripeApiException stripeError)
                LOG.error("Stripe Checkout failed: upstream Stripe status={} type={} code={} param={} requestId={}",
                        stripeError.statusCode(), stripeError.errorType(), stripeError.errorCode(),
                        stripeError.parameter(), stripeError.requestId());
            else
                LOG.error("Stripe Checkout failed: internal error type {}", exception.getClass().getName());
            return HttpResponse.status(HttpStatus.BAD_GATEWAY).body(java.util.Map.of("message", "Stripe Checkout konnte nicht erstellt werden."));
        }
    }
    @Post("/pools/{ownerUserId}/checkout") public HttpResponse<?> poolCheckout(UUID ownerUserId, @QueryValue(value = "demo", defaultValue = "false") String demo, @Body @Valid TipPaymentRequest request) {
        if ("1".equals(demo) || "true".equalsIgnoreCase(demo)) return HttpResponse.badRequest(java.util.Map.of("message", "Demo-Zahlungen sind deaktiviert."));
        try {
            var checkout = checkoutService.createPool(ownerUserId, request);
            if (checkout.isPresent()) return HttpResponse.ok(checkout.get());
            return HttpResponse.status(HttpStatus.SERVICE_UNAVAILABLE).body(java.util.Map.of("message", "Stripe Checkout ist noch nicht verfügbar."));
        } catch (StripeCheckoutService.PricingNotReleasedException blocked) {
            return HttpResponse.status(HttpStatus.SERVICE_UNAVAILABLE).body(java.util.Map.of("code","PRICING_NOT_RELEASED","message",blocked.getMessage()));
        } catch (TipPricing.NoDistributableTipException small) {
            return tipTooSmall(small);
        } catch (StripeCheckoutService.InvalidCheckoutRequestException invalid) {
            return HttpResponse.badRequest(java.util.Map.of("code","INVALID_CHECKOUT_REQUEST","message",invalid.getMessage()));
        } catch (StripeCheckoutService.CheckoutConflictException conflict) {
            return HttpResponse.status(HttpStatus.CONFLICT).body(conflict.response());
        } catch (StripeCheckoutService.QuoteChangedException changed) {
            return HttpResponse.status(HttpStatus.CONFLICT).body(java.util.Map.of("code","QUOTE_CHANGED","message",changed.getMessage()));
        } catch (com.gratilo.payment.service.PoolPaymentLimits.InvalidPoolAmount invalid) {
            return poolAmountInvalid(invalid);
        } catch (StripeCheckoutService.PoolNotReadyException unavailable) {
            return HttpResponse.status(HttpStatus.CONFLICT).body(java.util.Map.of("code", "POOL_NOT_READY", "message", "Dieser Pool ist noch nicht zahlungsbereit. Alle aktiven Mitglieder mit Trinkgeldanteil müssen Stripe freigeschaltet haben."));
        } catch (Exception exception) {
            if (exception instanceof StripeCheckoutService.StripeApiException stripeError)
                LOG.error("Pool Stripe Checkout failed: upstream status={} type={} code={} param={} requestId={}", stripeError.statusCode(), stripeError.errorType(), stripeError.errorCode(), stripeError.parameter(), stripeError.requestId());
            else LOG.error("Pool Stripe Checkout failed: internal error type {} at {}", exception.getClass().getName(), safeLocation(exception));
            return HttpResponse.status(HttpStatus.BAD_GATEWAY).body(java.util.Map.of("message", "Stripe Checkout konnte nicht erstellt werden."));
        }
    }
    @io.micronaut.http.annotation.Error(global = true, exception = TipPricing.NoDistributableTipException.class)
    public HttpResponse<?> tipTooSmall(TipPricing.NoDistributableTipException failure) {
        return HttpResponse.badRequest(java.util.Map.of("code","TIP_TOO_SMALL_AFTER_FEES","message",failure.getMessage()));
    }
    @io.micronaut.http.annotation.Error(global=true, exception=com.gratilo.payment.service.PoolPaymentLimits.InvalidPoolAmount.class)
    public HttpResponse<?> poolAmountInvalid(com.gratilo.payment.service.PoolPaymentLimits.InvalidPoolAmount failure){
        return HttpResponse.badRequest(java.util.Map.of("code","POOL_TIP_OUT_OF_RANGE","message",failure.getMessage()));
    }
    private static String safeLocation(Exception failure) {
        // Locations aid diagnostics without logging messages, request bodies, keys or personal details.
        for(var frame:failure.getStackTrace())if(frame.getClassName().startsWith("com.gratilo.payment."))
            return frame.getClassName()+"."+frame.getMethodName()+":"+frame.getLineNumber();
        return "unavailable";
    }

}
