package com.gratilo.payment.service;

import com.gratilo.payment.api.TipPaymentRequest;
import com.gratilo.payment.domain.*;
import com.gratilo.payment.repository.*;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StripeCheckoutTest {
    @Test void unknownCollectorCannotReceiveTheApplicationCollectorPricingOrCreateASession() throws Exception {
        for (boolean covered : new boolean[]{false, true}) {
            var fixture = new StripeCheckoutTest();
            fixture.stripeResponse = StripeAccountFixtures.changed("defaults.responsibilities", Map.of(
                    "fees_collector", "unknown", "losses_collector", "stripe"), false);
            var service = fixture.service(new java.math.BigDecimal("3.15"));
            assertFalse(service.canReceiveTips(fixture.recipient));
            assertThrows(StripeCheckoutService.RecipientNotReadyException.class, () -> service.create(
                    fixture.recipient, new TipPaymentRequest(1000L, null, "stripe-collector", covered)));
            assertNull(fixture.stored);
            assertNull(fixture.savedQuote);
            assertEquals(0, fixture.creates);
        }
    }

    @Test void stripeCollectorDirectChargeUsesOnlyFivePercentWithBothCoverageChoices() throws Exception {
        for(long tip:new long[]{1000,2500,10000})for(boolean covered:new boolean[]{false,true}) {
            var f = new StripeCheckoutTest();
            f.stripeResponse = StripeAccountFixtures.changed("defaults.responsibilities",Map.of("fees_collector","stripe","losses_collector","stripe"),false);
            var service=f.service(new java.math.BigDecimal("3.15"));
            var quoted=service.quote(f.recipient,tip,covered);
            service.create(f.recipient,new TipPaymentRequest(tip,"Danke","stripe-model",covered));
            assertEquals(14,f.savedQuote.pricingVersion());assertEquals(quoted,f.savedQuote);
            assertEquals(quoted.platformFeeNetMinor()+quoted.platformFeeTaxMinor(),quoted.platformFeeMinor());
            assertEquals("acct_fixture",f.requestAccount);
            assertTrue(f.form.contains("payment_intent_data[application_fee_amount]="+quoted.platformFeeMinor()+"&"));
            assertFalse(f.form.contains("transfer_data"));
            var snapshot=TipPricingSnapshot.from(f.stored);
            assertEquals(quoted.platformFeeMinor(),snapshot.applicationFeeMinor());
            assertEquals("stripe",quoted.feesCollector());assertTrue(quoted.recipientAmountEstimated());
            assertEquals(quoted.totalAmountMinor()-quoted.platformFeeMinor()-quoted.estimatedStripeFeeMinor(),quoted.recipientAmountMinor());
            assertEquals("acct_fixture",StripePaymentContext.account(f.stored));
            var frozen=f.stored.getMetadata();
            service.create(f.recipient,new TipPaymentRequest(tip,"Danke","stripe-model",covered));
            assertEquals(1,f.creates);assertEquals(frozen,f.stored.getMetadata());
        }
    }

    @Test void frozenSessionResumesWithoutUsingTodaysAccountOrChangingItsFeeAgreement() throws Exception {
        var service = service(new java.math.BigDecimal("3.15"));
        var request = new TipPaymentRequest(1000L, null, "frozen-collector", false);
        service.create(recipient, request);
        var frozen = stored.getMetadata();
        int accountReads = reads;
        stripeResponse = StripeAccountFixtures.changed("defaults.responsibilities.fees_collector", "stripe", false);
        var resumed = service.create(recipient, request).orElseThrow();
        assertEquals(113, resumed.quote().platformFeeMinor());
        assertEquals(830, resumed.quote().recipientAmountMinor());
        assertEquals("acct_fixture", requestAccount);
        assertEquals(frozen, stored.getMetadata());
        assertEquals(accountReads, reads);
        assertEquals(1, creates);
    }

    private final UUID recipient = UUID.randomUUID();
    private TransactionEntity stored;
    private String form, stripeKey, requestAccount;
    private int creates;
    private com.gratilo.payment.api.TipQuote savedQuote;
    private boolean ready = true, available = true, failOnce, providerFailure;
    private boolean accountPresent = true;
    private String accountId = "acct_fixture";
    private String stripeResponse;
    private String stripeSessionStatus = "complete", stripePaymentStatus = "paid";
    private int reads;
    private final String session = "cs_test_fixture";

    @Test void directChargeUsesFrozenNetAndApplicationFeeAcrossRatesAndCoverage() throws Exception {
        for(long tip:new long[]{1000,2500})for(String rate:List.of("1.5","2.8","3.15"))for(boolean covered:new boolean[]{true,false}) {
            var f=new StripeCheckoutTest();var decimal=new java.math.BigDecimal(rate);
            f.service(decimal).create(f.recipient,new TipPaymentRequest(tip,"Danke","unique",covered));
            var expected=TipPricing.directApplicationVatQuote(tip,covered,decimal,25,TipPricing.DEFAULT_PLATFORM_PERCENT);
            assertEquals(expected,f.savedQuote);
            assertFalse(f.form.contains("transfer_data"));
            assertEquals("acct_fixture",f.requestAccount);
            assertTrue(f.form.contains("unit_amount]="+expected.totalAmountMinor()+"&"));
            assertTrue(f.form.contains("application_fee_amount]="+(expected.totalAmountMinor()-expected.recipientAmountMinor())+"&"));
            assertEquals(tip,f.stored.getAmountMinor());assertEquals("Danke",f.stored.getDescription());
        }
    }

    @Test void directRequestsTransmitCommissionPlusStripeContributionInTheConnectedAccountScope() throws Exception {
        for(long tip:new long[]{1000,2500,10000})for(boolean covered:new boolean[]{false,true}) {
            var fixture=new StripeCheckoutTest();
            var expected=TipPricing.directApplicationVatQuote(tip,covered,new java.math.BigDecimal("3.15"),25,TipPricing.DEFAULT_PLATFORM_PERCENT);
            fixture.service(new java.math.BigDecimal("3.15")).create(fixture.recipient,new TipPaymentRequest(tip,"Danke","fee-proof",covered));
            assertEquals("acct_fixture",fixture.requestAccount);
            assertTrue(fixture.form.contains("payment_intent_data[application_fee_amount]="+(expected.totalAmountMinor()-expected.recipientAmountMinor())+"&"));
            assertFalse(fixture.form.contains("transfer_data"));
            assertEquals(1,fixture.creates);
        }
    }

    @Test void staleNetAmountIsRejectedEvenWhenGuestTotalHasNotChanged() throws Exception {
        var f=new StripeCheckoutTest();
        assertThrows(StripeCheckoutService.QuoteChangedException.class,()->f.service(new java.math.BigDecimal("3.15")).create(f.recipient,
            new TipPaymentRequest(2500L,"Danke","stale-net",false,2500L,2400L)));
        assertEquals(0,f.creates);assertNull(f.stored);
    }
    @Test void historicUncoveredSessionCannotBeRepricedOrReusedUnderNewRule() throws Exception {
        var f=new StripeCheckoutTest();var service=f.service();
        service.create(f.recipient,new TipPaymentRequest(2500L,null,"old-net",false));
        f.stored.setMetadata("{\"totalAmountMinor\":2500,\"feeCovered\":false}");
        assertEquals(2500,service.create(f.recipient,new TipPaymentRequest(2500L,null,"old-net",false)).orElseThrow().quote().recipientAmountMinor());
        assertEquals(1,f.creates);assertEquals(2500,TipPricingSnapshot.from(f.stored).recipientMinor());
    }
    @Test void stripeApiDiagnosticContainsOnlyHttpStatus() {
        var failure = StripeCheckoutService.StripeApiException.from(400,
                "{\"error\":{\"type\":\"invalid_request_error\",\"code\":\"parameter_unknown\",\"param\":\"payment_intent_data[test]\",\"message\":\"contains secret and account data\"}}",
                "req_test123");
        assertEquals(400, failure.statusCode());
        assertEquals("Stripe API returned HTTP 400", failure.getMessage());
        assertEquals("invalid_request_error", failure.errorType());
        assertEquals("parameter_unknown", failure.errorCode());
        assertEquals("payment_intent_data[test]", failure.parameter());
        assertEquals("req_test123", failure.requestId());
        assertFalse(failure.toString().contains("contains secret"));
        var malformed = StripeCheckoutService.StripeApiException.from(400, "not json", "untrusted request id");
        assertEquals("unknown", malformed.errorType());
        assertEquals("unknown", malformed.requestId());
    }

    @Test void staleDisplayedTotalCannotCreateAReplacementCheckout() throws Exception {
        assertThrows(StripeCheckoutService.QuoteChangedException.class, () -> service().create(recipient,
                new TipPaymentRequest(500L,"Danke","stale-quote",true,532L)));
        assertEquals(0,creates);assertNull(stored);
    }

    @Test void processedCheckoutReturnsSafeConflictWithOriginalSessionWithoutAnotherStripePost() throws Exception {
        var service=service();var request=new TipPaymentRequest(500L,"Danke","already-paid",true);
        service.create(recipient,request);stored.setStatus(TransactionStatus.BOOKED);
        var conflict=assertThrows(StripeCheckoutService.CheckoutConflictException.class,()->service.create(recipient,request));
        assertEquals("CHECKOUT_ALREADY_PROCESSED",conflict.response().get("code"));
        assertEquals(session,conflict.response().get("sessionId"));assertEquals(1,creates);
    }
    @Test void changedDetailsKeepOriginalAttemptAndNeverCreateAnotherPayment() throws Exception {
        var service=service();service.create(recipient,new TipPaymentRequest(500L,null,"frozen",false));
        var conflict=assertThrows(StripeCheckoutService.CheckoutConflictException.class,()->service.create(recipient,new TipPaymentRequest(1000L,null,"frozen",true)));
        assertEquals("CHECKOUT_DETAILS_CONFLICT",conflict.response().get("code"));assertEquals(500,stored.getAmountMinor());assertEquals(1,creates);
    }

    @Test void invalidAttemptKeyIsAnInputErrorBeforeAnyStripeRequest() {
        assertThrows(StripeCheckoutService.InvalidCheckoutRequestException.class,()->service().create(recipient,new TipPaymentRequest(500L,null,"invalid:key",false)));
        assertNull(stored);assertEquals(0,creates);
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class[]{type}, handler);
    }
    private StripeCheckoutService service() { return service(new java.math.BigDecimal("1.5")); }
    private StripeCheckoutService service(java.math.BigDecimal rate) {return service(rate,true);}
    private StripeCheckoutService service(java.math.BigDecimal rate,boolean enabled) {
        var repository = proxy(TransactionRepository.class, (p, m, a) -> switch (m.getName()) {
            case "findByAccountUserIdAndIdempotencyKey" -> Optional.ofNullable(stored);
            case "findById" -> Optional.ofNullable(stored);
            case "findByProviderReference" -> Optional.ofNullable(stored).filter(t->Objects.equals(t.getProviderReference(),a[0]));
            case "save" -> { stored = (TransactionEntity) a[0]; stored.setId(UUID.randomUUID()); yield stored; }
            case "update" -> a[0];
            default -> throw new AssertionError(m.getName());
        });
        var accounts = proxy(ConnectedAccountRepository.class, (p, m, a) -> {
            if (m.getName().equals("update")) return a[0];
            if (!accountPresent) return Optional.empty();
            var account = new ConnectedAccountEntity(); account.setStripeAccountId(accountId);
            account.setChargesEnabled(true); account.setPayoutsEnabled(true); account.setOnboardingStatus("COMPLETE");
            return Optional.of(account);
        });
        var connect = new StripeConnectService("sk_test_fixture", "http://localhost:3000") {
            @Override public String retrieveAccount(String id) {
                reads++;
                if (providerFailure) throw new IllegalStateException("Stripe unavailable");
                if (stripeResponse != null) return stripeResponse;
                return ready ? StripeAccountFixtures.readyJson() : StripeAccountFixtures.readyJson().replace("active", "pending");
            }
        };
        var recipients = new RecipientAvailability("http://localhost") {
            @Override public void requireEnabled(UUID user) { if (!available) throw new IllegalStateException("paused"); }
        };
        var feeAudit=new StripeFeeAuditService(null,null,"sk_test_fixture") {
            @Override public void snapshot(TransactionEntity tx,com.gratilo.payment.api.TipQuote quote){savedQuote=quote;}
        };
        return new StripeCheckoutService(repository, accounts, connect, "sk_test_fixture", "http://localhost/pay/success", "http://localhost/pay/cancelled", rate, 25, recipients,null,null,feeAudit,TipPricing.DEFAULT_PLATFORM_PERCENT,45,enabled) {
            @Override protected String stripeRequest(String path,String body,String key,String account){requestAccount=account;return stripeRequest(path,body,key);}
            @Override protected String stripeRequest(String path, String body, String key) {
                if (body != null) {
                    creates++; form = URLDecoder.decode(body, StandardCharsets.UTF_8); stripeKey = key;
                    if (failOnce) { failOnce = false; throw new IllegalStateException("transport failed"); }
                }
                return "{\"id\":\"" + session + "\",\"url\":\"https://checkout.stripe.com/test\",\"livemode\":false,\"status\":\"" + stripeSessionStatus + "\",\"payment_status\":\"" + stripePaymentStatus + "\",\"amount_total\":533,\"metadata\":{\"transaction_id\":\"" + stored.getId() + "\"}}";
            }
        };
    }

    @Test void unreleasedPricingBlocksNewSessionsButDoesNotRepriceAnExistingSession()throws Exception {
        var rate=new java.math.BigDecimal("3.15");
        assertThrows(StripeCheckoutService.PricingNotReleasedException.class,()->service(rate,false).create(recipient,new TipPaymentRequest(2500L,null,"flag",true)));
        assertNull(stored);assertEquals(0,creates);
        service(rate,true).create(recipient,new TipPaymentRequest(2500L,null,"flag",true));
        var resumed=service(new java.math.BigDecimal("6.8"),false).create(recipient,new TipPaymentRequest(2500L,null,"flag",true)).orElseThrow();
        assertEquals(2607,resumed.quote().totalAmountMinor());assertEquals(2298,resumed.quote().recipientAmountMinor());
        assertEquals(new java.math.BigDecimal("3.15"),resumed.quote().serviceFeePercent());assertEquals(1,creates);
    }
    @Test void preexistingDirectSessionRetainsFixedPlusPercentCommissionOnIdempotentResume()throws Exception {
        var request=new TipPaymentRequest(2500L,null,"historical-v4",true);
        var service=service(new java.math.BigDecimal("3.15"));service.create(recipient,request);
        var metadata=io.micronaut.json.JsonMapper.createDefault().readValue(stored.getMetadata(),Map.class);
        metadata.put("pricingVersion",4);metadata.put("pricingRule","DIRECT_APPLICATION_V4");
        metadata.put("platformFeeFixedMinor",45);metadata.put("platformFeeMinor",170);
        metadata.put("recipientAmountMinor",2330);metadata.put("applicationFeeMinor",277);
        stored.setMetadata(io.micronaut.json.JsonMapper.createDefault().writeValueAsString(metadata));
        var frozen=stored.getMetadata();
        var result=service.create(recipient,request).orElseThrow();
        assertEquals(4,result.quote().pricingVersion());assertEquals(170,result.quote().platformFeeMinor());
        assertEquals(2330,result.quote().recipientAmountMinor());assertEquals(1,creates);
        assertEquals("acct_fixture",requestAccount);assertEquals(frozen,stored.getMetadata());
    }
    @Test void ambiguousRetryAfterRetentionWindowCannotSendAnotherCheckoutPost()throws Exception {
        failOnce=true;var request=new TipPaymentRequest(2500L,null,"timeout",true);
        assertThrows(IllegalStateException.class,()->service().create(recipient,request));
        stored.setCreatedAt(java.time.OffsetDateTime.now().minusHours(24));
        assertThrows(StripeCheckoutService.CheckoutConflictException.class,()->service().create(recipient,request));
        assertEquals(1,creates);
    }

    @Test void routesOnlyTipToOneEmployeeAndReusesCheckout() throws Exception {
        var service = service();
        var request = new TipPaymentRequest(500L, "Danke", "caller-key", true);
        var first = service.create(recipient, request).orElseThrow();
        assertEquals(533,savedQuote.totalAmountMinor());assertEquals(33,savedQuote.serviceFeeMinor());
        assertTrue(form.contains("unit_amount]=533&"));
        assertFalse(form.contains("transfer_data"));
        assertTrue(form.contains("application_fee_amount]=116&"));
        assertEquals(417,savedQuote.recipientAmountMinor());
        assertTrue(form.contains("metadata][fee_covered]=true&"));
        assertTrue(form.contains("metadata][fee_surcharge_minor]=33&"));
        assertTrue(form.contains("metadata][amount_charged_minor]=533&"));
        assertFalse(form.contains("payment_method_types"));
        assertFalse(form.contains("customer"));
        assertEquals("checkout-" + stored.getId(), stripeKey);
        assertEquals(TransactionStatus.PENDING, stored.getStatus());
        // PostgreSQL JSONB normalizes whitespace and object key order.
        stored.setMetadata(io.micronaut.json.JsonMapper.createDefault().writeValueAsString(io.micronaut.json.JsonMapper.createDefault().readValue(stored.getMetadata(),Map.class)));
        assertEquals(first.transactionId(), service.create(recipient, request).orElseThrow().transactionId());
        assertEquals(1, creates);
        assertThrows(IllegalArgumentException.class, () -> service.create(recipient, new TipPaymentRequest(600L, "Danke", "caller-key", true)));
        assertThrows(IllegalArgumentException.class, () -> service.create(recipient, new TipPaymentRequest(500L, "Danke", "caller-key", false)));
    }

    @Test void retryAfterTransportFailureRetainsStripeIdempotency() throws Exception {
        var service = service(); failOnce = true;
        var request = new TipPaymentRequest(500L, null, "retry");
        assertThrows(IllegalStateException.class, () -> service.create(recipient, request));
        String originalKey = stripeKey;
        service.create(recipient, request);
        assertEquals(originalKey, stripeKey);
        assertEquals(2, creates);
    }

    @Test void rejectsPausedRecipientAndIncompleteStripeOnboarding() {
        var service = service(); ready = false;
        assertThrows(IllegalStateException.class, () -> service.create(recipient, new TipPaymentRequest(500L, null, "key")));
        ready = true; available = false;
        assertThrows(IllegalStateException.class, () -> service.create(recipient, new TipPaymentRequest(500L, null, "key")));
        assertNull(stored); assertEquals(0, creates);
    }

    @Test void missingInvalidAndDemoAccountsCannotCreateSessions() {
        var service = service();
        accountPresent = false;
        assertFalse(service.canReceiveTips(recipient));
        assertThrows(StripeCheckoutService.RecipientNotReadyException.class,
                () -> service.create(recipient, new TipPaymentRequest(500L, null, "missing")));
        accountPresent = true;
        for (String id : new String[]{null, "", "acct_demo_123", "not-an-account"}) {
            accountId = id;
            assertFalse(service.canReceiveTips(recipient));
            assertThrows(StripeCheckoutService.RecipientNotReadyException.class,
                    () -> service.create(recipient, new TipPaymentRequest(500L, null, "invalid")));
        }
        assertEquals(0, reads); assertEquals(0, creates); assertNull(stored);
    }

    @Test void rechecksStripeAfterPublicEligibilityAndRejectsRevokedOrUnavailableAccounts() throws Exception {
        var service = service();
        assertTrue(service.canReceiveTips(recipient));
        assertEquals(1, reads);
        ready = false;
        assertThrows(StripeCheckoutService.RecipientNotReadyException.class,
                () -> service.create(recipient, new TipPaymentRequest(500L, null, "revoked")));
        assertEquals(2, reads); assertEquals(0, creates); assertNull(stored);
        stripeResponse = "invalid JSON";
        assertFalse(service.canReceiveTips(recipient));
        assertThrows(Exception.class, () -> service.create(recipient, new TipPaymentRequest(500L, null, "malformed")));
        stripeResponse = null;
        providerFailure = true;
        assertFalse(service.canReceiveTips(recipient));
        assertThrows(IllegalStateException.class, () -> service.create(recipient, new TipPaymentRequest(500L, null, "offline")));
        assertEquals(0, creates); assertNull(stored);
    }

    @Test void everyV2IneligibleFixtureIsRejectedBeforeCreatingALedgerEntryOrSession() throws Exception {
        var service = service();
        for (String json : StripeAccountFixtures.ineligible()) {
            stripeResponse = json;
            if(StripeAccountStatus.directReady("acct_fixture",json))continue; // direct payments don't require transfer/payout capability
            assertFalse(service.canReceiveTips(recipient));
            assertThrows(IllegalStateException.class, () -> service.create(recipient, new TipPaymentRequest(500L, null, "blocked")));
            assertEquals(0, creates); assertNull(stored);
        }
    }

    @Test void confirmsOnlyMatchingSessionWithoutRevivingRefunds() throws Exception {
        var service = service(); service.create(recipient, new TipPaymentRequest(500L, null, "key"));
        assertFalse(service.confirmSession("cs_test_other"));
        assertTrue(service.confirmSession(session));
        assertNotNull(stored.getBookedAt());
        stored.setStatus(TransactionStatus.REFUNDED);
        assertFalse(service.confirmSession(session));
        assertEquals(TransactionStatus.REFUNDED, stored.getStatus());
        assertFalse(service.confirmSession("cs_live_rejected"));
    }

    @Test void openOrUnclearCheckoutCannotBeRetriedUntilStripeConfirmsItExpired() throws Exception {
        var service = service(); service.create(recipient, new TipPaymentRequest(500L, null, "key"));
        stored.setStatus(TransactionStatus.FAILED);
        stripeSessionStatus = "open"; stripePaymentStatus = "unpaid";
        var pending = service.checkoutStatus(session);
        assertEquals("PENDING", pending.status());
        assertNotNull(pending.checkoutUrl());
        stripeSessionStatus = "expired";
        var failed = service.checkoutStatus(session);
        assertEquals("FAILED", failed.status());
        assertNull(failed.checkoutUrl());
    }
}
