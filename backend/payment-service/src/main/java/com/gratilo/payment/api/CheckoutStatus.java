package com.gratilo.payment.api;

import io.micronaut.serde.annotation.Serdeable;
@Serdeable public record CheckoutStatus(String status, boolean confirmed, String transactionId,
        long tipAmountMinor, long amountPaidMinor, String recipientUserId, String checkoutUrl, long recipientAmountMinor, boolean recipientAmountEstimated, String poolId, String poolName) {
    public CheckoutStatus(String status,boolean confirmed,String transactionId,long tipAmountMinor,long amountPaidMinor,String recipientUserId,String checkoutUrl,long recipientAmountMinor,boolean estimated){this(status,confirmed,transactionId,tipAmountMinor,amountPaidMinor,recipientUserId,checkoutUrl,recipientAmountMinor,estimated,null,null);}
    public CheckoutStatus(String status,boolean confirmed,String transactionId,long tipAmountMinor,long amountPaidMinor,String recipientUserId,String checkoutUrl,long recipientAmountMinor){this(status,confirmed,transactionId,tipAmountMinor,amountPaidMinor,recipientUserId,checkoutUrl,recipientAmountMinor,false);}
    public CheckoutStatus(String status,boolean confirmed,String transactionId,long tipAmountMinor,long amountPaidMinor,String recipientUserId,String checkoutUrl){this(status,confirmed,transactionId,tipAmountMinor,amountPaidMinor,recipientUserId,checkoutUrl,tipAmountMinor);}
}
