package com.gratilo.payment.api;

import io.micronaut.serde.annotation.Serdeable;
@Serdeable public record CheckoutStatus(String status, boolean confirmed, String transactionId,
        long tipAmountMinor, long amountPaidMinor, String recipientUserId, String checkoutUrl) {}
