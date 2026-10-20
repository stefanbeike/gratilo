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
    @Test void waitsUntilStripeReportsTheChargeBalanceAvailable() {
        var fixture = new Fixture("pending", TransactionStatus.BOOKED, "PENDING", 0);
        fixture.service.settle();
        assertFalse(fixture.requests.stream().anyMatch(r -> r.startsWith("POST /v1/transfers ")));
        assertEquals("PENDING", fixture.allocation.getStatus());
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

    @Test void doesNotTransferMoreThanCurrentlyAvailablePlatformBalance() {
        var fixture = new Fixture("available", TransactionStatus.BOOKED, "PENDING", 0, 499);
        fixture.service.settle();
        assertEquals("PENDING", fixture.allocation.getStatus());
        assertFalse(fixture.requests.stream().anyMatch(r -> r.startsWith("POST /v1/transfers ")));
    }

    private static final class Fixture {
        final UUID transactionId = UUID.randomUUID();
        final UUID allocationId = UUID.randomUUID();
        final TransactionEntity tx = new TransactionEntity();
        final PoolTransferEntity allocation = new PoolTransferEntity();
        final List<String> requests = new CopyOnWriteArrayList<>();
        final String balanceState;
        final PoolTransferSettlementService service;
        Fixture(String balanceState, TransactionStatus transactionStatus, String allocationStatus, long reversal) {
            this(balanceState, transactionStatus, allocationStatus, reversal, 500);
        }
        Fixture(String balanceState, TransactionStatus transactionStatus, String allocationStatus, long reversal, long availableBalance) {
            this.balanceState = balanceState;
            tx.setId(transactionId); tx.setStatus(transactionStatus); tx.setProvider("STRIPE"); tx.setProviderReference("cs_test_fixture");
            allocation.setId(allocationId); allocation.setTransactionId(transactionId); allocation.setRecipientUserId(UUID.randomUUID());
            allocation.setStripeAccountId("acct_test_fixture"); allocation.setAmountMinor(500); allocation.setReversalAmountMinor(reversal);
            allocation.setReversedAmountMinor(0); allocation.setStatus(allocationStatus); allocation.setCreatedAt(OffsetDateTime.now()); allocation.setUpdatedAt(OffsetDateTime.now());
            var txRepo = (TransactionRepository) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{TransactionRepository.class},
                    (p,m,a) -> m.getName().equals("findById") ? Optional.of(tx) : defaultValue(m.getReturnType()));
            var transferRepo = (PoolTransferRepository) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{PoolTransferRepository.class},
                    (p,m,a) -> switch (m.getName()) {
                        case "findByStatus" -> allocation.getStatus().equals(a[0]) ? List.of(allocation) : List.of();
                        case "update" -> allocation;
                        default -> defaultValue(m.getReturnType());
                    });
            service = new PoolTransferSettlementService(transferRepo, txRepo, "sk_test_fixture", true) {
                @Override protected String request(String path, String body, String key) {
                    requests.add((body == null ? "GET " : "POST ") + path + " " + Objects.toString(body, "") + " KEY=" + Objects.toString(key, ""));
                    if (path.startsWith("/v1/checkout/sessions/")) return "{\"livemode\":false,\"payment_intent\":\"pi_fixture\"}";
                    if (path.startsWith("/v1/payment_intents/")) return "{\"livemode\":false,\"latest_charge\":{\"id\":\"ch_fixture\",\"balance_transaction\":{\"status\":\"" + balanceState + "\"}}}";
                    if (path.equals("/v1/balance")) return "{\"livemode\":false,\"available\":[{\"currency\":\"eur\",\"amount\":" + availableBalance + "}]}";
                    if (path.equals("/v1/transfers")) return "{\"livemode\":false,\"id\":\"tr_fixture\"}";
                    if (path.equals("/v1/transfers/tr_fixture/reversals")) return "{\"livemode\":false,\"id\":\"trr_fixture\"}";
                    throw new AssertionError(path);
                }
            };
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
