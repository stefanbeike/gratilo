package com.gratilo.payment.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Enrollment itself is the immutable new-payment boundary; no provider discovery creates rows. */
@Entity @Table(name="stripe_payment_audit",schema="payment_mgmt") @Getter @Setter @NoArgsConstructor
public class StripePaymentAuditEntity {
    @Id @Column(name="payment_id") private UUID paymentId;
    @Column(name="payment_type") private String paymentType;
    private boolean livemode;
    @Column(name="platform_account_id") private String platformAccountId;
    @Column(name="charge_account_id") private String chargeAccountId;
    @Column(name="checkout_session_id") private String checkoutSessionId;
    @Column(name="payment_intent_id") private String paymentIntentId;
    @Column(name="charge_id") private String chargeId;
    @Column(name="balance_transaction_id") private String balanceTransactionId;
    private String currency;
    @Column(name="expected_gross_minor") private long expectedGrossMinor;
    @Column(name="recipient_target_minor") private long recipientTargetMinor;
    private String status="PENDING";
    @Column(name="financial_data",columnDefinition="text") private String financialData="{}";
    @Column(name="external_costs",columnDefinition="text") private String externalCosts="[]";
    @Column(name="snapshot_hash") private String snapshotHash;
    private long revision;
    @Column(name="last_error") private String lastError;
    @Column(name="created_at",updatable=false) private OffsetDateTime createdAt=OffsetDateTime.now();
    @Column(name="checked_at") private OffsetDateTime checkedAt;
    @Column(name="next_check_at") private OffsetDateTime nextCheckAt=OffsetDateTime.now();
}
