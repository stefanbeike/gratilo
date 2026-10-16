package com.gratilo.payment.api;
import io.micronaut.serde.annotation.Serdeable;
@Serdeable
public record AccountSummary(long bookedBalanceMinor, long receivedMinor, long pendingMinor, long transactionCount, String currency) {}
