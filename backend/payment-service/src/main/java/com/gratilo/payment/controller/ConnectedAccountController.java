package com.gratilo.payment.controller;
import com.gratilo.payment.api.ConnectedAccountResponse;
import com.gratilo.payment.repository.ConnectedAccountRepository;
import io.micronaut.http.*;
import io.micronaut.http.annotation.*;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.rules.SecurityRule;
import java.util.UUID;
import java.time.OffsetDateTime;
import com.gratilo.payment.service.StripeConnectService;
@Controller("/payment-service/connect-account") @Secured(SecurityRule.IS_AUTHENTICATED)
public class ConnectedAccountController {
    private final ConnectedAccountRepository repository;
    private final StripeConnectService stripe;
    public ConnectedAccountController(ConnectedAccountRepository repository, StripeConnectService stripe) { this.repository = repository; this.stripe = stripe; }
    @Get public HttpResponse<?> get(Authentication authentication) { return repository.findByUserId(UUID.fromString(authentication.getName())).map(a -> HttpResponse.ok(ConnectedAccountResponse.from(a))).orElseGet(HttpResponse::notFound); }
    @Post("/onboarding/start") public HttpResponse<?> start(Authentication authentication) {
        UUID userId = UUID.fromString(authentication.getName());
        var existing = repository.findByUserId(userId);
        var account = existing.orElseGet(() -> { var a = new com.gratilo.payment.domain.ConnectedAccountEntity(); a.setUserId(userId); return a; });
        boolean isNew = existing.isEmpty();
        try {
            if (account.getStripeAccountId() == null || (account.getStripeAccountId().startsWith("acct_demo_") && stripe.isConfigured())) { String email = String.valueOf(authentication.getAttributes().getOrDefault("email", "")); String body = stripe.createAccount(email, stripe.paymentUrl(userId.toString())); String accountId = body == null ? "acct_demo_" + UUID.randomUUID().toString().replace("-", "") : StripeConnectService.field(body, "id"); if (accountId == null) throw new IllegalStateException("Stripe-Antwort enthält keine Account-ID"); account.setStripeAccountId(accountId); }
            stripe.ensureBusinessWebsite(account.getStripeAccountId(), stripe.paymentUrl(userId.toString()));
            String session = stripe.createOnboardingSession(account.getStripeAccountId());
            account.setOnboardingStatus("IN_PROGRESS");
            // Micronaut Data's save() uses persist for a new entity. An account
            // loaded above is detached by the time this transaction runs and
            // must therefore be updated instead.
            if (isNew) repository.save(account); else repository.update(account);
            return HttpResponse.ok(session == null ? ConnectedAccountResponse.from(account) : ConnectedAccountResponse.withSecret(account, StripeConnectService.field(session, "client_secret")));
        } catch (Exception e) {
            String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return HttpResponse.<java.util.Map<String, String>>status(HttpStatus.BAD_GATEWAY)
                    .body(java.util.Map.of("message", "Stripe-Onboarding konnte nicht gestartet werden.", "detail", detail));
        }
    }
    @Post("/onboarding/demo-complete") public HttpResponse<?> demoComplete(Authentication authentication) {
        var account = repository.findByUserId(UUID.fromString(authentication.getName())).orElse(null);
        if (account == null) return HttpResponse.notFound();
        account.setOnboardingStatus("COMPLETE"); account.setDetailsSubmitted(true); account.setChargesEnabled(true); account.setPayoutsEnabled(true);
        return HttpResponse.ok(ConnectedAccountResponse.from(repository.update(account)));
    }
    @Post("/sync") public HttpResponse<?> sync(Authentication authentication) {
        var account = repository.findByUserId(UUID.fromString(authentication.getName())).orElse(null);
        if (account == null || account.getStripeAccountId() == null || account.getStripeAccountId().startsWith("acct_demo_")) return account == null ? HttpResponse.notFound() : HttpResponse.ok(ConnectedAccountResponse.from(account));
        try {
            String body = stripe.retrieveAccount(account.getStripeAccountId());
            account.setChargesEnabled(StripeConnectService.booleanField(body, "charges_enabled")); account.setPayoutsEnabled(StripeConnectService.booleanField(body, "payouts_enabled")); account.setDetailsSubmitted(StripeConnectService.booleanField(body, "details_submitted"));
            account.setOnboardingStatus(account.isChargesEnabled() && account.isPayoutsEnabled() ? "COMPLETE" : "IN_PROGRESS");
            return HttpResponse.ok(ConnectedAccountResponse.from(repository.update(account)));
        } catch (Exception e) { return HttpResponse.serverError(java.util.Map.of("message", "Stripe-Status konnte nicht synchronisiert werden.")); }
    }
}
