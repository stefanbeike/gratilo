package com.gratilo.payment.api;

import io.micronaut.serde.annotation.Serdeable;
import java.time.OffsetDateTime;
import java.util.UUID;

@Serdeable
public record PoolTransferResponse(UUID id, UUID transactionId, String poolName, long amountMinor,
                                   long reversedAmountMinor, String status, String stripeTransferId,
                                   String stripeReversalId, OffsetDateTime createdAt) {}
