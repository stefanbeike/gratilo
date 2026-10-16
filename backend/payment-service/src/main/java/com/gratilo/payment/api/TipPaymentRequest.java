package com.gratilo.payment.api;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.*;
@Serdeable @Introspected public record TipPaymentRequest(@NotNull @Positive Long tipAmountMinor, @Size(max=500) String message, @Size(max=100) String idempotencyKey, boolean feeCovered) {
    public TipPaymentRequest(Long tipAmountMinor, String message, String idempotencyKey) { this(tipAmountMinor, message, idempotencyKey, false); }
}
