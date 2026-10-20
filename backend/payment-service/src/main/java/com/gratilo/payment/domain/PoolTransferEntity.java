package com.gratilo.payment.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "pool_transfers", schema = "payment_mgmt",
        uniqueConstraints = @UniqueConstraint(name = "pool_transfer_transaction_member_uq", columnNames = {"transaction_id", "recipient_user_id"}))
@Getter @Setter @NoArgsConstructor
public class PoolTransferEntity {
    @Id private UUID id;
    @Column(name = "transaction_id", nullable = false) private UUID transactionId;
    @Column(name = "recipient_user_id", nullable = false) private UUID recipientUserId;
    @Column(name = "stripe_account_id", nullable = false, length = 255) private String stripeAccountId;
    @Column(name = "share_percent", nullable = false, precision = 7, scale = 4) private java.math.BigDecimal sharePercent;
    @Column(name = "amount_minor", nullable = false) private long amountMinor;
    @Column(nullable = false, length = 16) private String status = "PENDING";
    @Column(name = "stripe_transfer_id", unique = true, length = 255) private String stripeTransferId;
    @Column(name = "stripe_reversal_id", length = 255) private String stripeReversalId;
    @Column(name = "reversal_amount_minor", nullable = false) private long reversalAmountMinor;
    @Column(name = "reversed_amount_minor", nullable = false) private long reversedAmountMinor;
    @Column(name = "failure_code", length = 80) private String failureCode;
    @Column(name = "created_at", nullable = false) private OffsetDateTime createdAt;
    @Column(name = "updated_at", nullable = false) private OffsetDateTime updatedAt;
}
