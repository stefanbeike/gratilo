package com.gratilo.payment.service;

import com.gratilo.payment.api.*;
import jakarta.inject.Singleton;
import java.util.*;

/** Outside the checkout transaction: finish admission only AFTER its commit succeeded. */
@Singleton
public class PoolCheckoutCoordinator {
    private final PoolDistributionClient pools;
    private final PoolCheckoutGate gate;
    private final StripeCheckoutService checkout;
    public PoolCheckoutCoordinator(PoolDistributionClient pools,PoolCheckoutGate gate,StripeCheckoutService checkout){this.pools=pools;this.gate=gate;this.checkout=checkout;}
    public Optional<CheckoutResponse> create(UUID poolRef,TipPaymentRequest request) throws Exception {
        PoolDistributionClient.Distribution pool;
        try {pool=pools.find(poolRef).orElseThrow(StripeCheckoutService.PoolNotReadyException::new);}
        catch(StripeCheckoutService.PoolNotReadyException missing){throw missing;}
        catch(Exception unavailable){throw new StripeCheckoutService.PoolEligibilityUnavailableException(unavailable);}
        var reservation=gate.admit(pool.poolId());
        try {
            var result=checkout.createPool(pool.poolId(),request);
            gate.finish(reservation);
            return result;
        } catch (StripeCheckoutService.PoolNotReadyException | StripeCheckoutService.InvalidCheckoutRequestException |
                 StripeCheckoutService.CheckoutConflictException | StripeCheckoutService.QuoteChangedException |
                 PoolPaymentLimits.InvalidPoolAmount | TipPricing.NoDistributableTipException |
                 StripeCheckoutService.PoolEligibilityUnavailableException | StripeCheckoutService.PricingNotReleasedException safe) {
            gate.finish(reservation);throw safe;
        }
        // Other failures may occur after Stripe accepted a request but before DB commit.
        // Keep the durable reservation open; reconciliation must establish the actual outcome.
    }
}
