package com.gratilo.user.api;

import com.gratilo.user.domain.UserEntity;
import io.micronaut.serde.annotation.Serdeable;

import java.time.OffsetDateTime;
import java.util.UUID;

public final class UserResponses {
    private UserResponses() {
    }

    @Serdeable
    public record Message(String message) {
    }

    @Serdeable
    public record Login(
            String tokenType,
            String accessToken,
            UUID userId,
            String email,
            boolean emailVerified
    ) {
    }

    @Serdeable
    public record Profile(
            UUID userId,
            String email,
            boolean emailVerified,
            String firstName,
            String lastName,
            String organisation,
            String street,
            String city,
            String phone,
            String country,
            boolean newsletter,
            String agbAcceptedFile,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            String profileImage,
            String paymentUrl,
            boolean paymentEnabled,
            boolean stripeOnboardingCompleted,
            boolean personalLinkPaused
    ) {
        public static Profile from(UserEntity user) { return from(user, false); }
        public static Profile from(UserEntity user, boolean paused) {
            return new Profile(
                    user.getId(), user.getEmail(), user.isEmailVerified(), user.getFirstName(), user.getLastName(),
                    user.getOrganisation(), user.getStreet(), user.getCity(), user.getPhone(), user.getCountry(),
                    user.isNewsletter(), user.getAgbAcceptedFile(), user.getCreatedAt(), user.getUpdatedAt(), user.getProfileImage(), user.getPaymentUrlPath(), user.isPaymentEnabled(), user.isStripeOnboardingCompleted(), false
            );
        }
    }

    @Serdeable
    public record PublicProfile(UUID userId, String firstName, String lastName, String organisation, String profileImage, String paymentUrl, boolean paymentEnabled, String poolName, boolean personalLinkPaused, boolean poolParticipationEnabled) {
        public static PublicProfile from(UserEntity user) { return from(user, null); }
        public static PublicProfile from(UserEntity user, String poolName) {
            // A team never replaces the employee's personal payment identity.
            return new PublicProfile(user.getId(), user.getFirstName(), user.getLastName(), user.getOrganisation(), user.getProfileImage(), user.getPaymentUrlPath(), user.isPaymentEnabled(), null, false, user.isPaymentEnabled());
        }
    }
}
