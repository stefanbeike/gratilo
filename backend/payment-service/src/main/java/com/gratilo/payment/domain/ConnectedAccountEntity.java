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
@Entity @Table(name="connected_accounts", schema="payment_mgmt") @Getter @Setter @NoArgsConstructor
public class ConnectedAccountEntity {
    @Id @GeneratedValue private UUID id;
    @Column(name="user_id", nullable=false, unique=true) private UUID userId;
    @Column(name="stripe_account_id", unique=true) private String stripeAccountId;
    @Column(name="onboarding_status", nullable=false) private String onboardingStatus = "NOT_STARTED";
    @Column(nullable=false) private boolean chargesEnabled;
    @Column(nullable=false) private boolean payoutsEnabled;
    @Column(nullable=false) private boolean detailsSubmitted;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="requirements_due", columnDefinition="jsonb", nullable=false) private String requirementsDue = "[]";
    @Column(name="payout_schedule_interval", nullable=false) private String payoutScheduleInterval = "weekly";
    @CreationTimestamp @Column(name="created_at", nullable=false, updatable=false) private OffsetDateTime createdAt;
    @UpdateTimestamp @Column(name="updated_at", nullable=false) private OffsetDateTime updatedAt;
}
