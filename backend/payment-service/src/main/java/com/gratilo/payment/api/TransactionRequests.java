package com.gratilo.payment.api;
import com.gratilo.payment.domain.TransactionType;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.*;
@Serdeable @Introspected
public record TransactionRequests(
        @NotNull @Positive Long amountMinor,
        @Pattern(regexp = "[A-Z]{3}") String currency,
        @NotNull TransactionType type,
        @Size(max = 140) String reference,
        @Size(max = 500) String description,
        @Size(max = 100) String idempotencyKey
) {}
