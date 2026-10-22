package com.gratilo.user.api;

import io.micronaut.serde.annotation.Serdeable;
import java.util.UUID;

/** Versioned immutable payment evidence, not a request to change a money movement. */
@Serdeable
public record TipNotification(String notificationKey, UUID transactionId, UUID recipientUserId,
        UUID allocationId, int templateVersion, int pricingVersion, long tipAmountMinor,
        long platformFeeMinor, long estimatedStripeFeeMinor, boolean feeCovered,
        long recipientTotalMinor, long personalAmountMinor, boolean pool) {
    public void validate(){
        if(transactionId==null||recipientUserId==null||!key(transactionId,recipientUserId).equals(notificationKey)
                ||pool!=(allocationId!=null)||pricingVersion<1||pricingVersion>8
                ||personalAmountMinor<=0||personalAmountMinor>99_999_999)
            throw new IllegalArgumentException("Invalid tip notification identity or allocation");
        if(templateVersion==2) {
            if(tipAmountMinor!=0||platformFeeMinor!=0||estimatedStripeFeeMinor!=0||feeCovered||recipientTotalMinor!=0)
                throw new IllegalArgumentException("Recipient-only notification must not contain payment totals");
            return;
        }
        if(templateVersion!=1||tipAmountMinor<=0||tipAmountMinor>1_000_000||platformFeeMinor<0||estimatedStripeFeeMinor<0
            ||recipientTotalMinor<=0||recipientTotalMinor>tipAmountMinor+(pricingVersion==8?estimatedStripeFeeMinor:0)
            ||personalAmountMinor>recipientTotalMinor||(!pool&&personalAmountMinor!=recipientTotalMinor))
            throw new IllegalArgumentException("Invalid legacy notification snapshot");
    }
    /** Legacy payloads may be read, but totals never reach a new template or persisted handoff. */
    public TipNotification recipientOnly(){
        validate();
        return templateVersion==2?this:new TipNotification(notificationKey,transactionId,recipientUserId,allocationId,
                2,pricingVersion,0,0,0,false,0,personalAmountMinor,pool);
    }
    public String payload(io.micronaut.json.JsonMapper json) throws java.io.IOException {
        var n=recipientOnly();
        var values=new java.util.LinkedHashMap<String,Object>();
        values.put("notificationKey",n.notificationKey());values.put("transactionId",n.transactionId());
        values.put("recipientUserId",n.recipientUserId());if(n.allocationId()!=null)values.put("allocationId",n.allocationId());
        values.put("templateVersion",2);values.put("pricingVersion",n.pricingVersion());
        values.put("personalAmountMinor",n.personalAmountMinor());values.put("pool",n.pool());
        return json.writeValueAsString(values);
    }
    public static String key(UUID payment,UUID recipient){return "tip-paid:"+payment+":"+recipient;}
}
