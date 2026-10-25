package com.gratilo.payment.service;

import com.gratilo.payment.repository.ConnectedAccountRepository;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.inject.Singleton;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Every eligibility decision retrieves current v2 state; saved state is for display and auditing only. */
@Singleton
public class StripeStatusSync {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(StripeStatusSync.class);
    @jakarta.inject.Inject @io.micronaut.core.annotation.Nullable StripePayoutService payouts;
    @Serdeable
    public record Status(UUID userId, String status, String onboardingStatus, Boolean transfersEnabled,
                         Boolean payoutsEnabled, OffsetDateTime checkedAt) {}
    private final ConnectedAccountRepository accounts;
    private final StripeConnectService stripe;
    public StripeStatusSync(ConnectedAccountRepository accounts, StripeConnectService stripe) {
        this.accounts = accounts; this.stripe = stripe;
    }
    public Status refresh(UUID user) {
        var account = accounts.findByUserId(user).orElse(null);
        if (account == null) return new Status(user, "NOT_STARTED", "NOT_STARTED", false, false, OffsetDateTime.now());
        StripeAccountStatus status;
        if (account.getStripeAccountId() == null) status = StripeAccountStatus.notStarted();
        else try { status = StripeAccountStatus.parse(account.getStripeAccountId(), stripe.retrieveAccount(account.getStripeAccountId())); }
        catch (Exception unavailable) {
            // Do not turn a transient lookup problem into a Stripe restriction or
            // reveal account IDs, response bodies, or personal data in logs.
            if(unavailable instanceof StripeConnectService.StripeApiException stripeFailure)
                LOG.warn("Stripe connected-account status lookup failed: HTTP {} requestId={}",stripeFailure.statusCode(),safeRequestId(stripeFailure.requestId()));
            else LOG.warn("Stripe connected-account status lookup failed: {}", unavailable.getClass().getSimpleName());
            return new Status(user, "UNAVAILABLE", account.getOnboardingStatus(), null, null, account.getStatusCheckedAt());
        }
        // A successful HTTP response can still be incomplete (wrong mode,
        // unexpected object, or omitted Accounts-v2 includes). Do not persist
        // its fail-closed placeholder booleans over the last confirmed status.
        if ("UNAVAILABLE".equals(status.displayStatus()))
            return new Status(user, "UNAVAILABLE", account.getOnboardingStatus(), null, null, account.getStatusCheckedAt());
        status.applyTo(account); accounts.update(account);
        return new Status(user, status.displayStatus(), status.onboardingStatus(), status.canReceiveTransfers(), status.canPayout(), account.getStatusCheckedAt());
    }
    public boolean refreshAccount(String accountId) {
        var account = accounts.findByStripeAccountId(accountId).orElse(null);
        if(account==null)return true;
        var status=refresh(account.getUserId());
        if(!"UNAVAILABLE".equals(status.status())&&payouts!=null&&payouts.automaticPreparationEnabled())payouts.prepareAccount(account.getUserId());
        return !"UNAVAILABLE".equals(status.status());
    }
    private static String safeRequestId(String id){return id!=null&&id.matches("[A-Za-z0-9_]{1,80}")?id:"unavailable";}
}
