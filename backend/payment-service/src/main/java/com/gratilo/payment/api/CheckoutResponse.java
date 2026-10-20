package com.gratilo.payment.api;
import io.micronaut.serde.annotation.Serdeable;
@Serdeable public record CheckoutResponse(String checkoutUrl, String transactionId, boolean demo, @io.micronaut.core.annotation.Nullable TipQuote quote) {
    public CheckoutResponse(String checkoutUrl,String transactionId,boolean demo){this(checkoutUrl,transactionId,demo,null);}
}
