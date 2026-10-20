package com.gratilo.payment.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity @Table(name = "transactions", schema = "payment_mgmt") @Getter @Setter @NoArgsConstructor
public class TransactionEntity {
    @Id @GeneratedValue private UUID id;
    @Column(name = "account_user_id", nullable = false) private UUID accountUserId;
    @Column(name = "counterparty_user_id") private UUID counterpartyUserId;
    @Column(name = "amount_minor", nullable = false) private long amountMinor;
    @Column(nullable = false, length = 3) private String currency = "EUR";
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24) private TransactionType type;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24) private TransactionStatus status;
    @Column(length = 140) private String reference;
    @Column(length = 500) private String description;
    @Column(name = "idempotency_key", length = 100) private String idempotencyKey;
    @Column(nullable = false, length = 40) private String provider = "DEMO";
    @Column(name = "provider_reference") private String providerReference;
    @Column(name = "stripe_payment_intent_id", length = 255) private String stripePaymentIntentId;
    @Column(name = "stripe_charge_id", length = 255) private String stripeChargeId;
    @Column(name = "payment_method", length = 40) private String paymentMethod;
    @Column(name = "failure_code", length = 80) private String failureCode;
    @Column(name = "failure_message", length = 500) private String failureMessage;
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb", nullable = false) private String metadata = "{}";
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false) private OffsetDateTime createdAt;
    @Column(name = "booked_at") private OffsetDateTime bookedAt;
    @Column(name = "completed_at") private OffsetDateTime completedAt;
    @UpdateTimestamp @Column(name = "updated_at", nullable = false) private OffsetDateTime updatedAt;
}
