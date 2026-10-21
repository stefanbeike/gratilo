package com.gratilo.user.domain;
import jakarta.persistence.*; import lombok.*; import java.time.OffsetDateTime; import java.util.UUID;
@Entity @Table(name="pool_members",schema="user_mgmt") @Getter @Setter @NoArgsConstructor
public class PoolMemberEntity { @Id private UUID id; @Column(name="pool_id",nullable=false) private UUID poolId; @Column(name="user_id",nullable=false) private UUID userId; @Column(name="share_percent",nullable=false) private double sharePercent; @Column(nullable=false,length=16) private String status; @Column(nullable=false) private boolean active=true; @Column(name="deactivation_reason",length=16) private String deactivationReason; @Column(name="invitation_token",unique=true,length=120) private String invitationToken; @Column(name="invitation_expires_at") private OffsetDateTime invitationExpiresAt; @Column(name="created_at",nullable=false) private OffsetDateTime createdAt; @Column(name="responded_at") private OffsetDateTime respondedAt;
 public boolean requiresOwnerReactivation(){return !active && !"SELF".equals(deactivationReason);}
}
