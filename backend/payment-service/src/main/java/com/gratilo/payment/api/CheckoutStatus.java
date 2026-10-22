package com.gratilo.payment.api;

import io.micronaut.serde.annotation.Serdeable;
@Serdeable public record CheckoutStatus(String status, boolean confirmed, String transactionId,
        long tipAmountMinor, long amountPaidMinor, String recipientUserId, String checkoutUrl, long recipientAmountMinor, boolean recipientAmountEstimated) {
    public CheckoutStatus(String status,boolean confirmed,String transactionId,long tipAmountMinor,long amountPaidMinor,String recipientUserId,String checkoutUrl,long recipientAmountMinor){this(status,confirmed,transactionId,tipAmountMinor,amountPaidMinor,recipientUserId,checkoutUrl,recipientAmountMinor,false);}
    public CheckoutStatus(String status,boolean confirmed,String transactionId,long tipAmountMinor,long amountPaidMinor,String recipientUserId,String checkoutUrl){this(status,confirmed,transactionId,tipAmountMinor,amountPaidMinor,recipientUserId,checkoutUrl,tipAmountMinor);}
}
