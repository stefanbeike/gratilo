package com.gratilo.payment.service;

import com.gratilo.payment.domain.TransactionEntity;
import io.micronaut.json.JsonMapper;
import java.util.Map;

/** Immutable scope: a missing legacy context ALWAYS means the platform, never today's profile account. */
public final class StripePaymentContext {
    private StripePaymentContext() {}
    public static String account(TransactionEntity tx) {
        try {
            var metadata=JsonMapper.createDefault().readValue(tx.getMetadata(),Map.class);
            if (!"DIRECT".equals(metadata.get("chargeModel"))) {
                if (TipPricingSnapshot.isDirectVersion(TipPricingSnapshot.from(tx).version())) throw new IllegalStateException("Missing direct account context");
                return null;
            }
            if (!TipPricingSnapshot.isDirectVersion(TipPricingSnapshot.from(tx).version()) || !(TipPricingSnapshot.from(tx).version()==8?"stripe":"application").equals(metadata.get("feesCollector"))
                    || !(metadata.get("stripeAccountId") instanceof String id) || !id.matches("acct_[A-Za-z0-9]+"))
                throw new IllegalStateException("Invalid direct account context");
            return id;
        } catch(java.io.IOException failure) {throw new IllegalStateException("Invalid payment context",failure);}
    }
}
