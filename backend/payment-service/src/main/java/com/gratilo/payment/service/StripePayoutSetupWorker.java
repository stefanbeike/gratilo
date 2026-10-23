package com.gratilo.payment.service;

import com.gratilo.payment.repository.ConnectedAccountRepository;
import io.micronaut.context.annotation.Requires;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;

/** Repeats account preparation after missed redirects/events. Never creates a payout. */
@Singleton
@Requires(beans=jakarta.persistence.EntityManager.class)
@Requires(property="stripe.manual-payout-setup-enabled",value="true")
public class StripePayoutSetupWorker {
    private final ConnectedAccountRepository accounts;
    private final StripePayoutService payouts;
    public StripePayoutSetupWorker(ConnectedAccountRepository accounts,StripePayoutService payouts){this.accounts=accounts;this.payouts=payouts;}
    @Scheduled(initialDelay="30s",fixedDelay="${stripe.manual-payout-setup-interval:2m}")
    public void prepare(){
        if(!payouts.automaticPreparationEnabled())return;
        for(var account:accounts.findAll())if(account.getStripeAccountId()!=null)payouts.prepareAccount(account.getUserId());
    }
}
