package com.gratilo.user.api;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class UserRequests {
    public static final String PASSWORD_PATTERN =
            "^(?=.*\\p{Ll})(?=.*\\p{Lu})(?=.*[^\\p{L}\\p{N}\\s])\\S{8,}$";

    private UserRequests() {
    }

    @Serdeable
    @Introspected
    public record Login(@Email @NotBlank String email, @NotBlank String password) {
    }

    @Serdeable
    @Introspected
    public record Register(
            @Email @NotBlank String email,
            @NotBlank @Pattern(regexp = PASSWORD_PATTERN) String password,
            @NotBlank String confirmPassword,
            Boolean newsletter,
            String firstName,
            String lastName,
            String organisation,
            String street,
            String city,
            String country,
            String phone,
            String agbFileName,
            String privacyPolicy,
            @Size(max = 140000) String profileImage
    ) {
    }

    @Serdeable
    @Introspected
    public record PasswordResetRequest(@Email @NotBlank String email) {
    }

    @Serdeable
    @Introspected
    public record PasswordResetConfirm(
            @NotBlank String token,
            @NotBlank @Pattern(regexp = PASSWORD_PATTERN) String newPassword
    ) {
    }

    @Serdeable
    @Introspected
    public record ProfileUpdate(
            String firstName,
            String lastName,
            String organisation,
            String street,
            String city,
            String phone,
            String country,
            Boolean newsletter,
            String currentPassword,
            @Size(min = 8) String newPassword,
            String confirmPassword,
            @Size(max = 140000) String profileImage,
            Boolean paymentEnabled,
            Boolean stripeOnboardingCompleted
    ) {
        public ProfileUpdate(String firstName, String lastName, String organisation, String street, String city,
                             String phone, String country, Boolean newsletter, String currentPassword,
                             String newPassword, String confirmPassword, String profileImage) {
            this(firstName, lastName, organisation, street, city, phone, country, newsletter, currentPassword,
                    newPassword, confirmPassword, profileImage, null, null);
        }
    }

    @Serdeable
    @Introspected
    public record SendEmail(
            @Email @NotBlank String to,
            @NotBlank @Size(max = 200) String subject,
            @NotBlank @Size(max = 20_000) String text
    ) {
    }
}
