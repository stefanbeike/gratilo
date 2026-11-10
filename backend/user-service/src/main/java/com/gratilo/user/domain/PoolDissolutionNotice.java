package com.gratilo.user.domain;
import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;
import java.util.UUID;
@Entity @Table(name="pool_dissolution_outbox",schema="user_mgmt",uniqueConstraints=@UniqueConstraint(columnNames={"pool_id","user_id"}))
@Getter @Setter @NoArgsConstructor
public class PoolDissolutionNotice {
    @Id private UUID id;
    @Column(name="pool_id",nullable=false) private UUID poolId;
    @Column(name="user_id",nullable=false) private UUID userId;
    @Column(name="pool_name",nullable=false,length=120) private String poolName;
    @Column(nullable=false,length=16) private String status="PENDING";
    @Column(nullable=false) private int attempts;
    @Column(name="next_attempt_at",nullable=false) private OffsetDateTime nextAttemptAt;
    @Column(name="lease_token") private UUID leaseToken;
    @Column(name="lease_until") private OffsetDateTime leaseUntil;
    @Column(name="last_error",length=80) private String lastError;
    @Column(name="created_at",nullable=false) private OffsetDateTime createdAt;
    @Column(name="updated_at",nullable=false) private OffsetDateTime updatedAt;
}
