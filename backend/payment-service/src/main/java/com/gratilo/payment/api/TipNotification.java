package com.gratilo.payment.api;

import io.micronaut.serde.annotation.Serdeable;
import java.util.UUID;

/** Versioned immutable payment evidence, not a request to change a money movement. */
@Serdeable
public record TipNotification(String notificationKey, UUID transactionId, UUID recipientUserId,
        UUID allocationId, int templateVersion, int pricingVersion, long tipAmountMinor,
        long platformFeeMinor, long estimatedStripeFeeMinor, boolean feeCovered,
        long recipientTotalMinor, long personalAmountMinor, boolean pool) {
    public void validate(){
        if(transactionId==null||recipientUserId==null||templateVersion!=1||pricingVersion<1||pricingVersion>7
            ||!key(transactionId,recipientUserId).equals(notificationKey)||pool!=(allocationId!=null)
            ||tipAmountMinor<=0||tipAmountMinor>1_000_000||platformFeeMinor<0||estimatedStripeFeeMinor<0
            ||recipientTotalMinor<=0||recipientTotalMinor>tipAmountMinor||personalAmountMinor<=0
            ||personalAmountMinor>recipientTotalMinor||(!pool&&personalAmountMinor!=recipientTotalMinor))
            throw new IllegalArgumentException("Invalid tip notification snapshot");
    }
    public static String key(UUID payment,UUID recipient){return "tip-paid:"+payment+":"+recipient;}
}
