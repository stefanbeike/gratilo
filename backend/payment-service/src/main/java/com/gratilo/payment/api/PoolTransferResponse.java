package com.gratilo.payment.api;

import io.micronaut.serde.annotation.Serdeable;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.math.BigDecimal;
import io.micronaut.core.annotation.Nullable;

@Serdeable
public record PoolTransferResponse(UUID id, UUID transactionId, String poolName, long amountMinor,
                                   long reversedAmountMinor, String status, String stripeTransferId,
                                   String stripeReversalId, OffsetDateTime createdAt, String settlementReason, OffsetDateTime fundsAvailableAt, OffsetDateTime lastAttemptAt,
                                   @Nullable BigDecimal historicalSharePercent, @Nullable Long poolDistributionAmountMinor,
                                   @Nullable Long selectedTipAmountMinor, @Nullable Long personalGrossAmountMinor,
                                   @Nullable Long personalFeeAmountMinor, @Nullable String guestPaymentStatus) {
    public PoolTransferResponse(UUID id,UUID transactionId,String poolName,long amountMinor,long reversedAmountMinor,String status,
                                String stripeTransferId,String stripeReversalId,OffsetDateTime createdAt,String settlementReason,
                                OffsetDateTime fundsAvailableAt,OffsetDateTime lastAttemptAt) {
        this(id,transactionId,poolName,amountMinor,reversedAmountMinor,status,stripeTransferId,stripeReversalId,createdAt,
                settlementReason,fundsAvailableAt,lastAttemptAt,null,null,null,null,null,null);
    }
}
