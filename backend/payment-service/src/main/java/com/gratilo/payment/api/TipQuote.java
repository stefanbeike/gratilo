package com.gratilo.payment.api;
import io.micronaut.serde.annotation.Serdeable;
@Serdeable public record TipQuote(long tipAmountMinor, long serviceFeeMinor, long totalAmountMinor, String currency, int serviceFeePercent, long fixedFeeMinor) {}
