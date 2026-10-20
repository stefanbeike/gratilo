package com.gratilo.payment.api;
import com.gratilo.payment.domain.ConnectedAccountEntity;
import io.micronaut.serde.annotation.Serdeable;
import java.util.UUID;
@Serdeable public record ConnectedAccountResponse(UUID userId, String stripeAccountId, String onboardingStatus, boolean chargesEnabled, boolean payoutsEnabled, boolean detailsSubmitted, String payoutScheduleInterval, boolean transfersEnabled, String stripeStatus, java.time.OffsetDateTime statusCheckedAt) {
    public static ConnectedAccountResponse from(ConnectedAccountEntity a) { return new ConnectedAccountResponse(a.getUserId(), a.getStripeAccountId(), a.getOnboardingStatus(), a.isChargesEnabled(), a.isPayoutsEnabled(), a.isDetailsSubmitted(), a.getPayoutScheduleInterval(), a.isTransfersEnabled(), a.getStripeStatus(), a.getStatusCheckedAt()); }
}
