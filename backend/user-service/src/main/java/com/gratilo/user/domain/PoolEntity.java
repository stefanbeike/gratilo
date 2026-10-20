package com.gratilo.user.domain;
import jakarta.persistence.*; import lombok.*; import java.time.OffsetDateTime; import java.util.UUID;
@Entity @Table(name="pools",schema="user_mgmt") @Getter @Setter @NoArgsConstructor
public class PoolEntity { @Id private UUID id; @Column(name="owner_id",nullable=false) private UUID ownerId; @Column(nullable=false,length=120) private String name; @Column(name="profile_image",columnDefinition="TEXT") private String profileImage; @Column(name="created_at",nullable=false) private OffsetDateTime createdAt; }
