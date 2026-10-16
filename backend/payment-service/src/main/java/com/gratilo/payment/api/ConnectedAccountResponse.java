package com.gratilo.payment.api;
import com.gratilo.payment.domain.ConnectedAccountEntity;
import io.micronaut.serde.annotation.Serdeable;
import java.util.UUID;
@Serdeable public record ConnectedAccountResponse(UUID userId, String stripeAccountId, String onboardingStatus, boolean chargesEnabled, boolean payoutsEnabled, boolean detailsSubmitted, String payoutScheduleInterval, String onboardingClientSecret) {
    public static ConnectedAccountResponse from(ConnectedAccountEntity a) { return new ConnectedAccountResponse(a.getUserId(), a.getStripeAccountId(), a.getOnboardingStatus(), a.isChargesEnabled(), a.isPayoutsEnabled(), a.isDetailsSubmitted(), a.getPayoutScheduleInterval(), null); }
    public static ConnectedAccountResponse withSecret(ConnectedAccountEntity a, String secret) { return new ConnectedAccountResponse(a.getUserId(), a.getStripeAccountId(), a.getOnboardingStatus(), a.isChargesEnabled(), a.isPayoutsEnabled(), a.isDetailsSubmitted(), a.getPayoutScheduleInterval(), secret); }
}
