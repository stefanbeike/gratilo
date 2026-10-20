package com.gratilo.payment.service;

import com.gratilo.payment.api.TipPaymentRequest;
import com.gratilo.payment.domain.*;
import com.gratilo.payment.repository.*;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PoolCheckoutTest {
    @Test void chargesOnceOnPlatformAndSnapshotsOnlyConnectEligibleMembers() throws Exception {
        UUID owner = UUID.randomUUID(), first = UUID.randomUUID(), second = UUID.randomUUID();
        var poolClient = new PoolDistributionClient("http://localhost", "fixture", true) {
            @Override public Optional<Distribution> find(UUID id) { return Optional.of(new Distribution(UUID.randomUUID(), "Team",
                    List.of(new Member(first, "A", 50, true), new Member(second, "B", 50, true)))); }
        };
        var accounts = accountRepository(Map.of(first, "acct_first", second, "acct_second"));
        var connect = new StripeConnectService("sk_test_fixture", "https://gratilo.test") {
            @Override public String retrieveAccount(String id) { return StripeAccountFixtures.readyJson().replace("acct_fixture", id); }
        };
        var recipients = new RecipientAvailability("http://localhost") { @Override public void requireEnabled(UUID userId) {} };
        var stored = new java.util.concurrent.atomic.AtomicReference<TransactionEntity>();
        var transactionRepo = proxy(TransactionRepository.class, (p, m, a) -> switch (m.getName()) {
            case "findByAccountUserIdAndIdempotencyKey" -> Optional.ofNullable(stored.get());
            case "findById" -> Optional.ofNullable(stored.get()).filter(tx -> tx.getId().equals(a[0]));
            case "save", "update" -> { var tx = (TransactionEntity) a[0]; if (tx.getId() == null) tx.setId(UUID.randomUUID()); stored.set(tx); yield tx; }
            default -> defaultValue(m.getReturnType());
        });
        var snapshots = new ArrayList<PoolTransferEntity>();
        var transferRepo = proxy(PoolTransferRepository.class, (p, m, a) -> switch (m.getName()) {
            case "save" -> { snapshots.add((PoolTransferEntity) a[0]); yield a[0]; }
            case "findByTransactionId" -> snapshots.stream().filter(row -> row.getTransactionId().equals(a[0])).toList();
            case "findByStatus" -> snapshots.stream().filter(row -> row.getStatus().equals(a[0])).toList();
            case "update" -> a[0];
            default -> defaultValue(m.getReturnType());
        });
        var calls = new ArrayList<String>();
        var service = new StripeCheckoutService(transactionRepo, accounts, connect, "sk_test_fixture",
                "https://gratilo.test/pay/success?session_id={CHECKOUT_SESSION_ID}", "https://gratilo.test/pay/cancelled",
                1.5, 25, recipients, poolClient, transferRepo) {
            @Override protected String stripeRequest(String path, String form, String key) {
                calls.add(path + " " + Objects.toString(form, ""));
                return "{\"livemode\":false,\"id\":\"cs_test_pool\",\"url\":\"https://checkout.stripe.com/test\"}";
            }
        };
        var response = service.createPool(owner, new TipPaymentRequest(501L, "Team danke", "pool-key", true)).orElseThrow();
        assertEquals("https://checkout.stripe.com/test", response.checkoutUrl());
        var form = calls.getFirst();
        assertTrue(form.contains("line_items%5B0%5D%5Bprice_data%5D%5Bunit_amount%5D=534"));
        assertTrue(form.contains("payment_intent_data%5Btransfer_group%5D=gratilo_"));
        assertFalse(form.contains("transfer_data%5Bdestination%5D"));
        assertFalse(form.contains("payment_method_types"));
        assertEquals(2, snapshots.size());
        assertEquals(501, snapshots.stream().mapToLong(PoolTransferEntity::getAmountMinor).sum());
        assertTrue(snapshots.stream().allMatch(item -> item.getStatus().equals("PENDING")));
    }

    private static ConnectedAccountRepository accountRepository(Map<UUID, String> ids) {
        return proxy(ConnectedAccountRepository.class, (proxy, method, args) -> {
            if (method.getName().equals("findByUserId")) {
                String accountId = ids.get((UUID) args[0]);
                if (accountId == null) return Optional.empty();
                var account = new ConnectedAccountEntity(); account.setUserId((UUID) args[0]); account.setStripeAccountId(accountId);
                return Optional.of(account);
            }
            if (method.getName().equals("update")) return args[0];
            return defaultValue(method.getReturnType());
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class[]{type}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (type == Optional.class) return Optional.empty();
        if (type == List.class) return List.of();
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        return null;
    }
}
