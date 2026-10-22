package com.gratilo.payment.api;

import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.core.annotation.Nullable;
import java.util.UUID;

@Serdeable
public record PayoutRequest(@Nullable @Serdeable.Deserializable(using=MinorUnitsDeserializer.class) Long amountMinor, UUID requestId) { }
