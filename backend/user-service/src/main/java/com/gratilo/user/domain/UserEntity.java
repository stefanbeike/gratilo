package com.gratilo.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "users", schema = "user_mgmt")
@Getter
@Setter
@NoArgsConstructor
public class UserEntity {
    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "first_name")
    private String firstName;

    @Column(name = "last_name")
    private String lastName;

    @Column(name = "profile_image", columnDefinition = "TEXT")
    private String profileImage;

    private String organisation;
    private String street;
    private String city;
    private String phone;
    private String country;

    @Column(name = "payment_url_path", length = 512, unique = true)
    private String paymentUrlPath;

    @Column(name = "payment_enabled", nullable = false)
    private boolean paymentEnabled = true;

    @Column(name = "stripe_onboarding_completed", nullable = false)
    private boolean stripeOnboardingCompleted = false;

    @Column(nullable = false)
    private boolean newsletter;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified;

    @Column(name = "agb_accepted_file")
    private String agbAcceptedFile;

    @Column(name = "privacy_policy")
    private String privacyPolicy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
