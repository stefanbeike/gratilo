package com.gratilo.payment.service;

import com.gratilo.payment.repository.ConnectedAccountRepository;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.inject.Singleton;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Every eligibility decision retrieves current v2 state; saved state is for display and auditing only. */
@Singleton
public class StripeStatusSync {
    @jakarta.inject.Inject @io.micronaut.core.annotation.Nullable StripePayoutService payouts;
    @Serdeable
    public record Status(UUID userId, String status, String onboardingStatus, boolean transfersEnabled,
                         boolean payoutsEnabled, OffsetDateTime checkedAt) {}
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
        catch (Exception unavailable) { status = new StripeAccountStatus(false); }
        status.applyTo(account); accounts.update(account);
        return new Status(user, status.displayStatus(), status.onboardingStatus(), status.canReceiveTransfers(), status.canPayout(), account.getStatusCheckedAt());
    }
    public boolean refreshAccount(String accountId) {
        var account = accounts.findByStripeAccountId(accountId).orElse(null);
        if(account==null)return true;
        var status=refresh(account.getUserId());
        if(payouts!=null&&payouts.automaticPreparationEnabled())payouts.prepareAccount(account.getUserId());
        return !"UNAVAILABLE".equals(status.status());
    }
}
