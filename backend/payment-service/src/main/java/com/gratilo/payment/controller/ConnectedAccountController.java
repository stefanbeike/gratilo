package com.gratilo.payment.controller;
import com.gratilo.payment.api.ConnectedAccountResponse;
import com.gratilo.payment.repository.ConnectedAccountRepository;
import io.micronaut.http.*;
import io.micronaut.http.annotation.*;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.rules.SecurityRule;
import java.util.UUID;
import com.gratilo.payment.service.StripeConnectService;
@Controller("/payment-service/connect-account") @Secured(SecurityRule.IS_AUTHENTICATED)
@io.micronaut.scheduling.annotation.ExecuteOn(io.micronaut.scheduling.TaskExecutors.BLOCKING)
public class ConnectedAccountController {
    @jakarta.inject.Inject @io.micronaut.core.annotation.Nullable
    com.gratilo.payment.service.StripeBalanceService balances;
    private final ConnectedAccountRepository repository;
    private final StripeConnectService stripe;
    public ConnectedAccountController(ConnectedAccountRepository repository, StripeConnectService stripe) { this.repository = repository; this.stripe = stripe; }
    @Get public HttpResponse<?> get(Authentication authentication) { return repository.findByUserId(UUID.fromString(authentication.getName())).map(a -> HttpResponse.ok(ConnectedAccountResponse.from(a))).orElseGet(HttpResponse::notFound); }
    @Get("/balance") public HttpResponse<?> balance(Authentication authentication){
        try{
            if(balances==null)return HttpResponse.status(HttpStatus.SERVICE_UNAVAILABLE);
            return balances.balance(UUID.fromString(authentication.getName())).map(value->HttpResponse.ok(value).header("Cache-Control","no-store")).orElseGet(HttpResponse::notFound);
        }catch(Exception unavailable){return HttpResponse.status(HttpStatus.BAD_GATEWAY).body(java.util.Map.of("code","STRIPE_BALANCE_UNAVAILABLE","message","Dein Stripe-Guthaben konnte nicht geladen werden."));}
    }
    @Post("/onboarding/start") public HttpResponse<?> start(Authentication authentication) {
        if (!stripe.isConfigured()) return HttpResponse.status(HttpStatus.SERVICE_UNAVAILABLE);
        UUID userId = UUID.fromString(authentication.getName());
        var existing = repository.findByUserId(userId);
        var account = existing.orElseGet(() -> { var a = new com.gratilo.payment.domain.ConnectedAccountEntity(); a.setUserId(userId); return a; });
        boolean isNew = existing.isEmpty();
        try {
            if (account.getStripeAccountId() == null || (account.getStripeAccountId().startsWith("acct_demo_") && stripe.isConfigured())) { String email = String.valueOf(authentication.getAttributes().getOrDefault("email", "")); String body = stripe.createAccount(email, stripe.paymentUrl(userId.toString())); String accountId = StripeConnectService.field(body, "id"); if (accountId == null) throw new IllegalStateException("Stripe-Antwort enthält keine Account-ID"); account.setStripeAccountId(accountId); }
            // Persist the Stripe ID before creating the short-lived onboarding session.
            // A failed hosted-link request must not cause another connected account on retry.
            if (isNew) repository.save(account); else repository.update(account);
            stripe.ensureBusinessWebsite(account.getStripeAccountId(), stripe.paymentUrl(userId.toString()));
            String link = stripe.createOnboardingLink(account.getStripeAccountId());
            account.setOnboardingStatus("IN_PROGRESS");
            // Micronaut Data's save() uses persist for a new entity. An account
            // loaded above is detached by the time this transaction runs and
            // must therefore be updated instead.
            repository.update(account);
            String onboardingUrl = StripeConnectService.field(link, "url");
            if (onboardingUrl == null || !onboardingUrl.matches("https://connect\\.stripe\\.com/[^\\s]+"))
                throw new IllegalStateException("Stripe-Antwort enthält keinen gültigen Onboarding-Link");
            return HttpResponse.ok(java.util.Map.of("onboardingUrl", onboardingUrl)).header("Cache-Control", "no-store");
        } catch (Exception e) {
            String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return HttpResponse.<java.util.Map<String, String>>status(HttpStatus.BAD_GATEWAY)
                    .body(java.util.Map.of("message", "Stripe-Onboarding konnte nicht gestartet werden.", "detail", detail));
        }
    }
    @Post("/onboarding/demo-complete") public HttpResponse<?> demoComplete(Authentication authentication) {
        return HttpResponse.notFound();
    }
    @Post("/sync") public HttpResponse<?> sync(Authentication authentication) {
        var account = repository.findByUserId(UUID.fromString(authentication.getName())).orElse(null);
        if (account == null) return HttpResponse.notFound();
        if (account.getStripeAccountId() == null || !account.getStripeAccountId().matches("acct_[A-Za-z0-9]+")) {
            com.gratilo.payment.service.StripeAccountStatus.notStarted().applyTo(account);
            return HttpResponse.ok(ConnectedAccountResponse.from(repository.update(account)));
        }
        try {
            String body = stripe.retrieveAccount(account.getStripeAccountId());
            com.gratilo.payment.service.StripeAccountStatus.parse(account.getStripeAccountId(), body).applyTo(account);
            return HttpResponse.ok(ConnectedAccountResponse.from(repository.update(account)));
        } catch (Exception e) {
            new com.gratilo.payment.service.StripeAccountStatus(false).applyTo(account);
            repository.update(account);
            return HttpResponse.serverError(java.util.Map.of("message", "Stripe-Status konnte nicht synchronisiert werden."));
        }
    }
}
