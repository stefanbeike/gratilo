package com.gratilo.payment.api;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record PayoutRequest(Long amountMinor) { }
