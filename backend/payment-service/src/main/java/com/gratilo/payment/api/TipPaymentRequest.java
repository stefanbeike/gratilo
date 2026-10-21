package com.gratilo.payment.api;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.*;
@Serdeable @Introspected public record TipPaymentRequest(@NotNull @Min(100) @Max(1000000) Long tipAmountMinor, @Size(max=500) String message, @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,100}") String idempotencyKey, boolean feeCovered, @io.micronaut.core.annotation.Nullable @Positive Long expectedTotalAmountMinor, @io.micronaut.core.annotation.Nullable @Positive Long expectedRecipientAmountMinor, @io.micronaut.core.annotation.Nullable java.util.UUID expectedPoolId) {
    public TipPaymentRequest(Long amount,String message,String key,boolean covered,Long total,Long recipient) {this(amount,message,key,covered,total,recipient,null);}
    public TipPaymentRequest(Long tipAmountMinor, String message, String idempotencyKey, boolean feeCovered, Long expectedTotalAmountMinor) { this(tipAmountMinor, message, idempotencyKey, feeCovered, expectedTotalAmountMinor, null); }
    public TipPaymentRequest(Long tipAmountMinor, String message, String idempotencyKey, boolean feeCovered) { this(tipAmountMinor, message, idempotencyKey, feeCovered, null, null); }
    public TipPaymentRequest(Long tipAmountMinor, String message, String idempotencyKey) { this(tipAmountMinor, message, idempotencyKey, false); }
}
