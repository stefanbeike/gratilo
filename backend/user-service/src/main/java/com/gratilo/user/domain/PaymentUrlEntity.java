package com.gratilo.user.domain;
import jakarta.persistence.*; import lombok.*; import java.time.OffsetDateTime; import java.util.UUID;
@Entity @Table(name="payment_url_pool", schema="user_mgmt") @Getter @Setter @NoArgsConstructor
public class PaymentUrlEntity { @Id private Long id; @Column(name="payment_url",unique=true) private String paymentUrl; @Column(name="assigned_user_id",unique=true) private UUID assignedUserId; @Column(name="assigned_at") private OffsetDateTime assignedAt; }
