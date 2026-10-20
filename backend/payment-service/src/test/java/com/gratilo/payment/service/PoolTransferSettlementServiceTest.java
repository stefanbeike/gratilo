package com.gratilo.payment.service;

import com.gratilo.payment.domain.*;
import com.gratilo.payment.repository.*;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.jupiter.api.Assertions.*;

class PoolTransferSettlementServiceTest {
    @Test void schedulesConfirmedPaymentAgainstPendingSourceFunds() {
        var fixture = new Fixture("pending", TransactionStatus.BOOKED, "PENDING", 0);
        fixture.service.settle();
        assertTrue(fixture.requests.stream().anyMatch(r -> r.startsWith("POST /v1/transfers ")));
        assertEquals("TRANSFERRED", fixture.allocation.getStatus());
    }

    @Test void createsOneSourceLinkedTransferAfterFundsAreAvailableAndIsIdempotent() {
        var fixture = new Fixture("available", TransactionStatus.BOOKED, "PENDING", 0);
        fixture.service.settle();
        assertEquals("TRANSFERRED", fixture.allocation.getStatus());
        assertEquals("tr_fixture", fixture.allocation.getStripeTransferId());
        assertTrue(fixture.requests.stream().anyMatch(r -> r.contains("source_transaction=ch_fixture")));
        fixture.service.settle();
        assertEquals(1, fixture.requests.stream().filter(r -> r.startsWith("POST /v1/transfers ")).count());
    }

    @Test void reversesTransferredAmountsAfterRefundAndRetriesTheSameReversalSafely() {
        var fixture = new Fixture("pending", TransactionStatus.REFUNDED, "TRANSFERRED", 500);
        fixture.allocation.setStripeTransferId("tr_fixture");
        fixture.service.settle();
        assertEquals("REVERSED", fixture.allocation.getStatus());
        assertEquals(500, fixture.allocation.getReversedAmountMinor());
        assertEquals(1, fixture.requests.stream().filter(r -> r.startsWith("POST /v1/transfers/tr_fixture/reversals ")).count());
    }

    @Test void sourceLinkedTransferDoesNotDependOnAvailablePlatformBalance() {
        var fixture = new Fixture("available", TransactionStatus.BOOKED, "PENDING", 0, 0);
        fixture.service.settle();
        assertEquals("TRANSFERRED", fixture.allocation.getStatus());
        assertFalse(fixture.requests.stream().anyMatch(r -> r.startsWith("GET /v1/balance ")));
    }

    @Test void historicPaymentNeedsExplicitApprovalAndNeverCallsStripe() {
        var fixture = new Fixture("available", TransactionStatus.BOOKED, "PENDING", 0);
        fixture.allocation.setSettlementApproved(false);
        fixture.service.settle();
        assertEquals("APPROVAL_REQUIRED", fixture.allocation.getFailureCode());
        assertTrue(fixture.requests.isEmpty());
    }
    @Test void pendingPaymentCannotCreateTransfers() {
        var fixture = new Fixture("available", TransactionStatus.PENDING, "PENDING", 0);
        fixture.service.settle();
        assertEquals("WAITING_CONFIRMED_PAYMENT", fixture.allocation.getFailureCode());
        assertTrue(fixture.requests.isEmpty());
    }
    @Test void uncertainOldAttemptRequiresReconciliationInsteadOfAnotherPost() {
        var fixture = new Fixture("available", TransactionStatus.BOOKED, "PENDING", 0);
        fixture.allocation.setFirstAttemptAt(OffsetDateTime.now().minusDays(2));
        fixture.service.settle();
        assertEquals("RECONCILIATION_REQUIRED", fixture.allocation.getFailureCode());
        assertFalse(fixture.requests.stream().anyMatch(r -> r.startsWith("POST ")));
    }
    @Test void refundBeforeSettlementDoesNotProduceAnyTransfer() {
        var fixture = new Fixture("available", TransactionStatus.REFUNDED, "PENDING", 0);
        fixture.service.settle(); fixture.service.settle();
        assertEquals("REVERSED", fixture.allocation.getStatus());
        assertEquals(500, fixture.allocation.getReversedAmountMinor());
        assertFalse(fixture.requests.stream().anyMatch(r -> r.startsWith("POST ")));
    }
    @Test void remoteSuccessIsRecoveredWithoutPostingAgainAfterLostDatabaseState() {
        var fixture = new Fixture("available", TransactionStatus.BOOKED, "PENDING", 0);
        fixture.service.settle();
        fixture.allocation.setStripeTransferId(null); fixture.allocation.setStatus("PENDING");
        fixture.service.settle();
        assertEquals("tr_fixture", fixture.allocation.getStripeTransferId());
        assertEquals(1, fixture.requests.stream().filter(r -> r.startsWith("POST /v1/transfers ")).count());
    }
    @Test void restrictedRecipientNeverReceivesTransfer() {
        var fixture = new Fixture("available", TransactionStatus.BOOKED, "PENDING", 0);
        fixture.recipientReady = false; fixture.service.settle();
        assertEquals("RECIPIENT_NOT_READY", fixture.allocation.getFailureCode());
        assertFalse(fixture.requests.stream().anyMatch(r -> r.startsWith("POST ")));
    }
    @Test void partialRefundWithheldBeforeTransferIsNotReversedAgain() {
        var fixture = new Fixture("available", TransactionStatus.BOOKED, "PENDING", 100);
        fixture.transferAmount=400;
        fixture.service.settle(); fixture.service.settle();
        assertEquals(100, fixture.allocation.getReversedAmountMinor());
        assertEquals("TRANSFERRED", fixture.allocation.getStatus());
        assertEquals(1, fixture.requests.stream().filter(r -> r.startsWith("POST ")).count());
    }

    private static final class Fixture {
        final UUID transactionId = UUID.randomUUID();
        final UUID allocationId = UUID.randomUUID();
        final TransactionEntity tx = new TransactionEntity();
        final PoolTransferEntity allocation = new PoolTransferEntity();
        final List<String> requests = new CopyOnWriteArrayList<>();
        final String balanceState;
        boolean remoteCreated, recipientReady = true;
        long transferAmount = 500;
        final PoolTransferSettlementService service;
        Fixture(String balanceState, TransactionStatus transactionStatus, String allocationStatus, long reversal) {
            this(balanceState, transactionStatus, allocationStatus, reversal, 500);
        }
        Fixture(String balanceState, TransactionStatus transactionStatus, String allocationStatus, long reversal, long availableBalance) {
            this.balanceState = balanceState;
            tx.setAmountMinor(500); tx.setCurrency("EUR"); tx.setId(transactionId); tx.setStatus(transactionStatus); tx.setProvider("STRIPE"); tx.setProviderReference("cs_test_fixture");
            allocation.setId(allocationId); allocation.setTransactionId(transactionId); allocation.setRecipientUserId(UUID.randomUUID());
            allocation.setStripeAccountId("acct_test_fixture"); allocation.setAmountMinor(500); allocation.setReversalAmountMinor(reversal);
            allocation.setSettlementApproved(true); allocation.setReversedAmountMinor(0); allocation.setStatus(allocationStatus); allocation.setCreatedAt(OffsetDateTime.now()); allocation.setUpdatedAt(OffsetDateTime.now());
            var txRepo = (TransactionRepository) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{TransactionRepository.class},
                    (p,m,a) -> m.getName().equals("findById") ? Optional.of(tx) : defaultValue(m.getReturnType()));
            var transferRepo = (PoolTransferRepository) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{PoolTransferRepository.class},
                    (p,m,a) -> switch (m.getName()) {
                        case "findByStatus" -> allocation.getStatus().equals(a[0]) ? List.of(allocation) : List.of();
                        case "update" -> allocation;
                        case "findByTransactionId" -> List.of(allocation);
                        default -> defaultValue(m.getReturnType());
                    });
            service = new PoolTransferSettlementService(transferRepo, txRepo, "sk_test_fixture", true) {
                @Override protected String request(String path, String body, String key) {
                    requests.add((body == null ? "GET " : "POST ") + path + " " + Objects.toString(body, "") + " KEY=" + Objects.toString(key, ""));
                    if (path.startsWith("/v1/checkout/sessions/")) return "{\"livemode\":false,\"status\":\"complete\",\"payment_status\":\"paid\",\"payment_intent\":\"pi_fixture\"}";
                    if (path.startsWith("/v1/payment_intents/")) return "{\"livemode\":false,\"status\":\"succeeded\",\"latest_charge\":{\"id\":\"ch_fixture\",\"amount\":533,\"transfer_group\":\"gratilo_" + transactionId + "\",\"livemode\":false,\"paid\":true,\"captured\":true,\"currency\":\"eur\",\"balance_transaction\":{\"currency\":\"eur\",\"status\":\"" + balanceState + "\"}}}";
                    if (path.equals("/v1/balance")) return "{\"livemode\":false,\"available\":[{\"currency\":\"eur\",\"amount\":" + availableBalance + "}]}";
                    if (path.startsWith("/v2/core/accounts/")) return (recipientReady ? StripeAccountFixtures.readyJson() : StripeAccountFixtures.readyJson().replace("active", "restricted")).replace("acct_fixture", "acct_test_fixture");
                    if (path.startsWith("/v1/transfers?")) return "{\"data\":[" + (remoteCreated ? transferJson() : "") + "],\"has_more\":false}";
                    if (path.equals("/v1/transfers") || path.equals("/v1/transfers/tr_fixture")) { remoteCreated = true; return transferJson(); }
                    if (path.equals("/v1/transfers/tr_fixture/reversals")) return "{\"id\":\"trr_fixture\",\"transfer\":\"tr_fixture\",\"amount\":500}";
                    throw new AssertionError(path);
                }
            };
        }
        String transferJson() {
            return "{\"livemode\":false,\"id\":\"tr_fixture\",\"amount\":" + transferAmount + ",\"amount_reversed\":0,\"currency\":\"eur\",\"destination\":\"acct_test_fixture\",\"source_transaction\":\"ch_fixture\",\"metadata\":{\"pool_allocation_id\":\"" + allocationId + "\"}}";
        }
        static Object defaultValue(Class<?> type) {
            if (type == Optional.class) return Optional.empty();
            if (type == List.class) return List.of();
            if (type == boolean.class) return false;
            if (type == int.class) return 0;
            if (type == long.class) return 0L;
            return null;
        }
    }
}
