package com.gratilo.payment.api;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.*;
@Serdeable @Introspected public record TipPaymentRequest(@NotNull @Min(100) @Max(1000000) Long tipAmountMinor, @Size(max=500) String message, @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,100}") String idempotencyKey, boolean feeCovered, @io.micronaut.core.annotation.Nullable @Positive Long expectedTotalAmountMinor) {
    public TipPaymentRequest(Long tipAmountMinor, String message, String idempotencyKey, boolean feeCovered) { this(tipAmountMinor, message, idempotencyKey, feeCovered, null); }
    public TipPaymentRequest(Long tipAmountMinor, String message, String idempotencyKey) { this(tipAmountMinor, message, idempotencyKey, false); }
}
