package com.gratilo.payment.domain;
import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Created only for new checkouts; immutable quote and separately reconciled Stripe charge costs. */
@Entity @Table(name="payment_fee_audits", schema="payment_mgmt") @Getter @Setter @NoArgsConstructor
public class PaymentFeeAuditEntity {
    @Id @Column(name="transaction_id") private UUID transactionId;
    @Column(name="charged_minor", nullable=false) private long chargedMinor;
    @Column(name="surcharge_minor", nullable=false) private long surchargeMinor;
    @Column(name="estimated_fee_minor", nullable=false) private long estimatedFeeMinor;
    @Column(name="estimate_percent", nullable=false) private double estimatePercent;
    @Column(name="estimate_fixed_minor", nullable=false) private long estimateFixedMinor;
    @Column(name="estimate_basis", nullable=false) private String estimateBasis;
    @Column(nullable=false) private String status = "PENDING";
    @Column(name="actual_total_fee_minor") private Long actualTotalFeeMinor;
    @Column(name="actual_fee_minor") private Long actualFeeMinor;
    @Column(name="actual_net_minor") private Long actualNetMinor;
    @Column(name="difference_minor") private Long differenceMinor;
    @Column(name="uncovered_minor") private Long uncoveredMinor;
    @Column(name="balance_transaction_id") private String balanceTransactionId;
    @Column(name="fee_details", columnDefinition="text") private String feeDetails;
    @Column(name="last_error") private String lastError;
    @Column(name="checked_at") private OffsetDateTime checkedAt;
}
