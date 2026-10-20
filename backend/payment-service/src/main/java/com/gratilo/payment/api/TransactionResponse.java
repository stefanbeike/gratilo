package com.gratilo.payment.api;
import com.gratilo.payment.domain.*;
import io.micronaut.serde.annotation.Serdeable;
import java.time.OffsetDateTime;
import java.util.UUID;
@Serdeable
public record TransactionResponse(UUID id, UUID accountUserId, long amountMinor, String currency, TransactionType type,
                                  TransactionStatus status, String reference, String description, String provider,
                                  String providerReference, String paymentMethod, String failureCode, String failureMessage,
                                  OffsetDateTime createdAt, OffsetDateTime bookedAt, OffsetDateTime completedAt, String metadata, long recipientAmountMinor) {
    public TransactionResponse(UUID id,UUID accountUserId,long amountMinor,String currency,TransactionType type,TransactionStatus status,String reference,String description,String provider,String providerReference,String paymentMethod,String failureCode,String failureMessage,OffsetDateTime createdAt,OffsetDateTime bookedAt,OffsetDateTime completedAt,String metadata) {
        this(id,accountUserId,amountMinor,currency,type,status,reference,description,provider,providerReference,paymentMethod,failureCode,failureMessage,createdAt,bookedAt,completedAt,metadata,amountMinor);
    }
    public static TransactionResponse from(TransactionEntity t) { return new TransactionResponse(t.getId(), t.getAccountUserId(), t.getAmountMinor(), t.getCurrency(), t.getType(), t.getStatus(), t.getReference(), t.getDescription(), t.getProvider(), t.getProviderReference(), t.getPaymentMethod(), t.getFailureCode(), t.getFailureMessage(), t.getCreatedAt(), t.getBookedAt(), t.getCompletedAt(), t.getMetadata(), com.gratilo.payment.service.TipPricingSnapshot.creditedAmount(t)); }
}
