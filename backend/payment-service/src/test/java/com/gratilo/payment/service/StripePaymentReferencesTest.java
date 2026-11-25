package com.gratilo.payment.service;

import com.gratilo.payment.domain.TransactionEntity;
import com.gratilo.payment.domain.TransactionStatus;
import com.gratilo.payment.domain.TransactionType;
import com.gratilo.payment.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.gratilo.payment.service.StripeReferenceFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StripePaymentReferencesTest {
    @TestFactory
    java.util.stream.Stream<DynamicTest> scopedChargeFixtures() {
        return List.of("pool/paypal", "pool/klarna", "direct/klarna", "pool/card", "direct/card",
                        "pool/card/apple_pay", "direct/card/apple_pay", "pool/card/google_pay",
                        "direct/card/google_pay", "pool/p24", "pool/link", "direct/link", "pool/bancontact",
                        "pool/eps", "pool/mb_way", "pool/pay_by_bank", "pool/amazon_pay", "pool/blik", "pool/pix",
                        "pool/kakao_pay", "pool/naver_pay", "pool/payco", "pool/samsung_pay", "pool/satispay",
                        "direct/amazon_pay", "direct/bancontact", "direct/eps", "direct/mb_way", "direct/pay_by_bank",
                        "direct/blik", "direct/kakao_pay", "direct/naver_pay", "direct/payco", "direct/samsung_pay",
                        "direct/ideal", "direct/revolut_pay", "direct/swish", "direct/crypto").stream()
                .map(fixture -> DynamicTest.dynamicTest(fixture, () -> {
                    var fields = fixture.split("/");
                    assertScopedCharge(fields[0].equals("direct"), fields[1], fields.length == 3 ? fields[2] : null);
                }));
    }

    private void assertScopedCharge(boolean direct, String method, String wallet) {
        var tx = transaction(direct);
        var repo = mock(TransactionRepository.class);
        var store = mock(StripePaymentReferenceStore.class);
        when(repo.findById(tx.getId())).thenReturn(Optional.of(tx));
        var service = new StripePaymentReferences(store, repo);

        Map<String, ?> details = switch (method) {
            case "paypal" -> Map.of("transaction_id", "4YW59914EF9464227", "payer_email", "excluded@example.test");
            case "klarna" -> Map.of("payment_method_category", "pay_now", "preferred_locale", "de-DE");
            case "p24" -> Map.of("reference", "P24-A12-BC3-D45");
            case "amazon_pay", "kakao_pay", "naver_pay", "payco", "samsung_pay", "satispay", "ideal", "revolut_pay" ->
                    Map.of("transaction_id", "987654321");
            case "pix" -> Map.of("bank_transaction_id", "E18236120202611251130abcdef123456");
            case "swish" -> Map.of("payment_reference", "847362185");
            case "crypto" -> Map.of("transaction_hash", "0x92fd71c6b47eac6c6cd3e7bc9950f3aa9eeab896fbc549a22652a554fb058821");
            case "card" -> wallet == null ? Map.of("funding", "debit") : Map.of("wallet", Map.of("type", wallet));
            default -> Map.of();
        };
        var charge = charge(tx, "ch_3MmlLrLkdIwHu7ix0snN0B15", true, method, details);
        String account = direct ? "acct_Direct123" : null;
        service.webhook(event("charge.succeeded", charge, account), direct);

        var saved = org.mockito.ArgumentCaptor.forClass(StripeChargeReferences.Charge.class);
        verify(store).save(eq(tx.getId()), eq(false), eq(account), saved.capture());
        assertEquals(method, saved.getValue().method());
        assertEquals(wallet, saved.getValue().wallet());
        assertEquals(570L, saved.getValue().chargedMinor());
        assertEquals(Instant.ofEpochSecond(1679090560), saved.getValue().paidAt());
        assertNotNull(saved.getValue().receipt());
        assertEquals(List.of(), saved.getValue().references().stream().filter(r -> r.value().contains("@")).toList());
        assertEquals(List.of("paypal", "p24", "amazon_pay", "kakao_pay", "naver_pay", "payco", "samsung_pay", "satispay", "pix", "ideal", "revolut_pay", "swish", "crypto")
                .contains(method) ? 1 : 0, saved.getValue().references().size());
        assertEquals(TransactionStatus.PENDING, tx.getStatus());
        assertNull(tx.getStripeChargeId());
        verify(repo, never()).update(any());
    }

    @Test
    void mismatchedScopeModeAndIntentDoNotWriteReferences() {
        var tx = transaction(true);
        var repo = mock(TransactionRepository.class);
        var store = mock(StripePaymentReferenceStore.class);
        when(repo.findById(tx.getId())).thenReturn(Optional.of(tx));
        var service = new StripePaymentReferences(store, repo);
        var charge = charge(tx, "ch_3MmlLrLkdIwHu7ix0snN0B15", true, "card", Map.of());

        service.webhook(event("charge.updated", charge, "acct_Foreign123"), true);
        service.webhook(event("charge.updated", charge, "acct_Direct123"), false);
        service.webhook(event("charge.updated", charge, null), false);
        charge.put("livemode", true);
        service.webhook(event("charge.updated", charge, "acct_Direct123"), true);
        charge.put("livemode", false);
        charge.put("payment_intent", "pi_Foreign123");
        service.webhook(event("charge.updated", charge, "acct_Direct123"), true);
        verifyNoInteractions(store);
    }

    @Test
    void aPayPalPlatformChargeCannotBeAttachedThroughTheDirectEndpoint() {
        var tx = transaction(false);
        var repo = mock(TransactionRepository.class);
        var store = mock(StripePaymentReferenceStore.class);
        when(repo.findById(tx.getId())).thenReturn(Optional.of(tx));
        var service = new StripePaymentReferences(store, repo);
        var paypal = charge(tx, "ch_PayPal", true, "paypal", Map.of("transaction_id", "4YW59914EF9464227"));
        service.webhook(event("charge.succeeded", paypal, "acct_Direct123"), true);
        verifyNoInteractions(store);
    }

    @Test
    void independentDocumentedFieldsKeepTheirProvenance() {
        var charge = charge(transaction(false), "ch_Afterpay", true, "afterpay_clearpay",
                Map.of("order_id", "987654", "reference", "AFTERPAY123"));
        var parsed = StripeChargeReferences.read(charge, false, null);
        assertEquals(List.of(
                new StripeChargeReferences.Reference("Stripe Charge.payment_method_details.afterpay_clearpay.order_id", "987654"),
                new StripeChargeReferences.Reference("Stripe Charge.payment_method_details.afterpay_clearpay.reference", "AFTERPAY123")
        ), parsed.references());
    }

    @Test
    void unsupportedAndPersonalFieldsAreIgnoredAndUntrustedReceiptLinksAreDropped() {
        var charge = charge(transaction(false), "ch_1Example", true, "paypal",
                Map.of("capture_id", "UNDOCUMENTED_ID", "payer_id", "ACCOUNT_ID"));
        charge.put("receipt_url", "https://pay.stripe.com.example.invalid/receipts/link");
        var result = StripeChargeReferences.read(charge, false, null);
        assertTrue(result.references().isEmpty());
        assertNull(result.receipt());
    }

    @Test
    void chronologicalOrderDoesNotDetermineWhichReferencesCanBeCaptured() {
        var tx = transaction(false);
        var repo = mock(TransactionRepository.class);
        var store = mock(StripePaymentReferenceStore.class);
        when(repo.findById(tx.getId())).thenReturn(Optional.of(tx));
        var service = new StripePaymentReferences(store, repo);
        var later = charge(tx, "ch_Later", true, "paypal", Map.of("transaction_id", "4YW59914EF9464227"));
        var early = charge(tx, "ch_Later", true, "paypal", Map.of());
        var failure = charge(tx, "ch_Earlier", false, "card", Map.of());
        service.webhook(event("charge.updated", later, null), false);
        service.webhook(event("charge.succeeded", early, null), false);
        service.webhook(event("charge.failed", failure, null), false);

        var captured = org.mockito.ArgumentCaptor.forClass(StripeChargeReferences.Charge.class);
        verify(store, times(3)).save(eq(tx.getId()), eq(false), isNull(), captured.capture());
        var observations = captured.getAllValues();
        assertEquals("4YW59914EF9464227", observations.getFirst().references().getFirst().value());
        assertEquals(Instant.ofEpochSecond(1679090560), observations.get(1).paidAt());
        assertEquals("ch_Earlier", observations.getLast().id());
        assertNull(observations.getLast().chargedMinor());
    }

    @Test
    void backfillUsesStripeScopeAndStopsAtRateLimitWithAResumableCursor() throws Exception {
        var tx = transaction(true);
        var store = mock(StripePaymentReferenceStore.class);
        var client = mock(StripePaymentAuditClient.class);
        when(client.configured()).thenReturn(true);
        var start = new UUID(0, 0);
        when(store.batch(false, start, 2)).thenReturn(List.of(tx));
        String account = "acct_Direct123";
        when(client.get("/v1/checkout/sessions/" + tx.getProviderReference(), account)).thenReturn(
                object("object", "checkout.session", "id", tx.getProviderReference(), "livemode", false,
                        "metadata", object("transaction_id", tx.getId().toString()), "payment_intent", tx.getStripePaymentIntentId()));
        when(client.get("/v1/payment_intents/" + tx.getStripePaymentIntentId(), account))
                .thenThrow(new StripePaymentAuditClient.ReadFailure(429, "17"))
                .thenReturn(object("object", "payment_intent", "id", tx.getStripePaymentIntentId(), "livemode", false));
        when(client.get("/v1/charges?limit=100&payment_intent=" + tx.getStripePaymentIntentId(), account)).thenReturn(
                object("object", "list", "has_more", false, "data", List.of(charge(tx, "ch_Retry", true, "card", Map.of()))));

        var backfill = new StripePaymentReferenceBackfill(store, client);
        var stopped = backfill.run(false, true, null, 1);
        assertEquals(start, stopped.nextAfter());
        assertEquals(tx.getId(), stopped.failedTransactionId());
        assertEquals(17L, stopped.retryAfterSeconds());
        assertEquals(0, stopped.processed());
        var dryRun = backfill.run(false, true, stopped.nextAfter(), 1);
        assertEquals(tx.getId(), dryRun.nextAfter());
        assertEquals(1, dryRun.attempts());
        verify(store, never()).saveAll(any(), anyBoolean(), any(), anyList());

        assertEquals(1, backfill.run(false, false, start, 1).processed());
        verify(store).saveAll(eq(tx.getId()), eq(false), eq(account), anyList());
        assertThrows(IllegalArgumentException.class, () -> backfill.run(true, false, start, 1));
    }
}

final class StripeReferenceFixtures {
    private StripeReferenceFixtures() {}

    static TransactionEntity transaction(boolean direct) {
        var tx = new TransactionEntity();
        tx.setId(UUID.randomUUID());
        tx.setAccountUserId(UUID.randomUUID());
        tx.setType(TransactionType.TIP);
        tx.setStatus(TransactionStatus.PENDING);
        tx.setProvider("STRIPE");
        tx.setProviderReference("cs_test_a1StripeFixture01");
        tx.setStripePaymentIntentId("pi_3MmlLrLkdIwHu7ixFixture");
        tx.setAmountMinor(500);
        if (direct) {
            var quote = TipPricing.compensationQuote(500, false, new java.math.BigDecimal("3.15"), 25,
                    TipPricing.DEFAULT_PLATFORM_PERCENT, TipPricing.DEFAULT_PLATFORM_VAT_PERCENT, false);
            var metadata = TipPricingSnapshot.metadata(quote, false);
            metadata.put("chargeModel", "DIRECT");
            metadata.put("stripeAccountId", "acct_Direct123");
            tx.setMetadata(StripePaymentAuditJson.write(metadata));
        } else {
            tx.setMetadata("{\"poolId\":\"52e9d687-cc14-4b80-a5b2-162b1230a6c2\"}");
        }
        return tx;
    }

    static Map<String, Object> charge(TransactionEntity tx, String id, boolean paid, String method, Map<String, ?> details) {
        return object("object", "charge", "id", id, "payment_intent", tx.getStripePaymentIntentId(),
                "created", 1679090539, "currency", "eur", "livemode", false, "amount", 570,
                "status", paid ? "succeeded" : "failed", "paid", paid, "captured", paid, "amount_captured", paid ? 570 : 0,
                "receipt_url", paid ? "https://pay.stripe.com/receipts/payment/CAcaFixture" : null,
                "metadata", object("transaction_id", tx.getId().toString()),
                "payment_method_details", object("type", method, method, details));
    }

    static Map<String, Object> event(String type, Map<String, Object> charge, String account) {
        return object("object", "event", "id", "evt_1StripeFixture", "type", type, "livemode", false,
                "account", account, "created", 1679090560, "data", object("object", charge));
    }

    static Map<String, Object> object(Object... pairs) {
        var values = new java.util.LinkedHashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) values.put((String) pairs[i], pairs[i + 1]);
        return values;
    }
}
