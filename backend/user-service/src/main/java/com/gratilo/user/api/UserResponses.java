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
            boolean stripeOnboardingCompleted
    ) {
        public static Profile from(UserEntity user) {
            return new Profile(
                    user.getId(), user.getEmail(), user.isEmailVerified(), user.getFirstName(), user.getLastName(),
                    user.getOrganisation(), user.getStreet(), user.getCity(), user.getPhone(), user.getCountry(),
                    user.isNewsletter(), user.getAgbAcceptedFile(), user.getCreatedAt(), user.getUpdatedAt(), user.getProfileImage(), user.getPaymentUrlPath(), user.isPaymentEnabled(), user.isStripeOnboardingCompleted()
            );
        }
    }

    @Serdeable
    public record PublicProfile(UUID userId, String firstName, String lastName, String organisation, String profileImage, String paymentUrl, boolean paymentEnabled, String poolName) {
        public static PublicProfile from(UserEntity user) { return from(user, null); }
        public static PublicProfile from(UserEntity user, String poolName) {
            // Keep the pool name in the legacy name fields as well. This
            // allows older cached payment-page bundles (which do not know
            // about `poolName` yet) to display the correct recipient.
            String displayFirstName = poolName == null || poolName.isBlank() ? user.getFirstName() : poolName;
            String displayLastName = poolName == null || poolName.isBlank() ? user.getLastName() : null;
            return new PublicProfile(user.getId(), displayFirstName, displayLastName, user.getOrganisation(), user.getProfileImage(), user.getPaymentUrlPath(), user.isPaymentEnabled(), poolName);
        }
    }
}
