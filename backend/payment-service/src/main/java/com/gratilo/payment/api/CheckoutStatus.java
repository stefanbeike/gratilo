package com.gratilo.payment.api;

import io.micronaut.serde.annotation.Serdeable;
@Serdeable public record CheckoutStatus(String status, boolean confirmed, String transactionId,
        long tipAmountMinor, long amountPaidMinor, String recipientUserId, String checkoutUrl, long recipientAmountMinor, boolean recipientAmountEstimated, String poolId, String poolName,
        String publicReference, String currency, String tipCurrency, String paidAt, String paymentMethod, String wallet, String receiptUrl) {
    public CheckoutStatus(String status, boolean confirmed, String transactionId, long tipAmountMinor, long amountPaidMinor,
            String recipientUserId, String checkoutUrl, long recipientAmountMinor, boolean estimated, String poolId, String poolName) {
        this(status, confirmed, transactionId, tipAmountMinor, amountPaidMinor, recipientUserId, checkoutUrl,
                recipientAmountMinor, estimated, poolId, poolName, null, null, null, null, null, null, null);
    }
    public CheckoutStatus(String status,boolean confirmed,String transactionId,long tipAmountMinor,long amountPaidMinor,String recipientUserId,String checkoutUrl,long recipientAmountMinor,boolean estimated){this(status,confirmed,transactionId,tipAmountMinor,amountPaidMinor,recipientUserId,checkoutUrl,recipientAmountMinor,estimated,null,null);}
    public CheckoutStatus(String status,boolean confirmed,String transactionId,long tipAmountMinor,long amountPaidMinor,String recipientUserId,String checkoutUrl,long recipientAmountMinor){this(status,confirmed,transactionId,tipAmountMinor,amountPaidMinor,recipientUserId,checkoutUrl,recipientAmountMinor,false);}
    public CheckoutStatus(String status,boolean confirmed,String transactionId,long tipAmountMinor,long amountPaidMinor,String recipientUserId,String checkoutUrl){this(status,confirmed,transactionId,tipAmountMinor,amountPaidMinor,recipientUserId,checkoutUrl,tipAmountMinor);}
}
