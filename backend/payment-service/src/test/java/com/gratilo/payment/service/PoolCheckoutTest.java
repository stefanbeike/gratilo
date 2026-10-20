package com.gratilo.payment.service;

import com.gratilo.payment.api.TipPaymentRequest;
import com.gratilo.payment.domain.*;
import com.gratilo.payment.repository.*;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PoolCheckoutTest {
    @Test void coveredChargePreservesPoolTipAndFeeSnapshot() throws Exception { assertPoolCharge(true); }
    @Test void uncoveredChargePreservesPoolTipWithoutSurcharge() throws Exception { assertPoolCharge(false); }
    private void assertPoolCharge(boolean covered) throws Exception {
        UUID owner = UUID.randomUUID(), first = UUID.randomUUID(), second = UUID.randomUUID();
        var poolClient = new PoolDistributionClient("http://localhost", "fixture", true) {
            @Override public Optional<Distribution> find(UUID id) { return Optional.of(new Distribution(UUID.randomUUID(), "Team",
                    List.of(new Member(first, "A", 50, true), new Member(second, "B", 50, true)))); }
        };
        var accounts = accountRepository(Map.of(first, "acct_first", second, "acct_second"));
        var connect = new StripeConnectService("sk_test_fixture", "https://gratilo.test") {
            @Override public String retrieveAccount(String id) { return StripeAccountFixtures.readyJson().replace("acct_fixture", id); }
        };
        var recipients = new RecipientAvailability("http://localhost") { @Override public void requireEnabled(UUID userId) {} @Override public void requirePoolMemberEnabled(UUID userId) {} };
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
        var auditRows=new HashMap<UUID,PaymentFeeAuditEntity>();
        var auditRepository=proxy(PaymentFeeAuditRepository.class,(p,m,a)->switch(m.getName()){
            case "findById" -> Optional.ofNullable(auditRows.get(a[0]));
            case "save" -> {var row=(PaymentFeeAuditEntity)a[0];auditRows.put(row.getTransactionId(),row);yield row;}
            default -> throw new AssertionError(m.getName());
        });
        var feeAudit=new StripeFeeAuditService(auditRepository,transactionRepo,"sk_test_fixture");
        var calls = new ArrayList<String>();
        var service = new StripeCheckoutService(transactionRepo, accounts, connect, "sk_test_fixture",
                "https://gratilo.test/pay/success?session_id={CHECKOUT_SESSION_ID}", "https://gratilo.test/pay/cancelled",
                1.5, 25, recipients, poolClient, transferRepo, feeAudit) {
            @Override protected String stripeRequest(String path, String form, String key) {
                calls.add(path + " " + Objects.toString(form, ""));
                return "{\"livemode\":false,\"id\":\"cs_test_pool\",\"url\":\"https://checkout.stripe.com/test\"}";
            }
        };
        var response = service.createPool(owner, new TipPaymentRequest(501L, "Team danke", "pool-key", covered)).orElseThrow();
        assertEquals("https://checkout.stripe.com/test", response.checkoutUrl());
        var form = calls.getFirst();
        assertTrue(form.contains("line_items%5B0%5D%5Bprice_data%5D%5Bunit_amount%5D="+(covered?534:501)));
        assertTrue(form.contains("payment_intent_data%5Btransfer_group%5D=gratilo_"));
        assertFalse(form.contains("transfer_data%5Bdestination%5D"));
        assertFalse(form.contains("payment_method_types"));
        assertEquals(2, snapshots.size());
        assertEquals(1,auditRows.size());var audit=auditRows.get(stored.get().getId());
        assertEquals(covered?534:501,audit.getChargedMinor());assertEquals(covered?33:0,audit.getSurchargeMinor());
        assertEquals("Team danke",stored.get().getDescription());
        assertTrue(snapshots.stream().allMatch(PoolTransferEntity::isSettlementApproved));
        assertEquals(501, snapshots.stream().mapToLong(PoolTransferEntity::getAmountMinor).sum());
        assertTrue(snapshots.stream().allMatch(item -> item.getStatus().equals("PENDING")));
    }

    @Test void blocksTheWholePoolUntilEveryRequiredMemberIsReadyWithoutRedistribution() throws Exception {
        UUID owner=UUID.randomUUID(), other=UUID.randomUUID();
        var pool=new PoolDistributionClient("http://localhost", "fixture", true) {
            @Override public Optional<Distribution> find(UUID id) { return Optional.of(new Distribution(UUID.randomUUID(), "Team", List.of(new Member(owner,"A",50,true),new Member(other,"B",50,true)))); }
        };
        var ready=new java.util.concurrent.atomic.AtomicBoolean(false);
        var connect=new StripeConnectService("sk_test_fixture", "http://localhost") {
            @Override public String retrieveAccount(String id) { return StripeAccountFixtures.readyJson().replace("acct_fixture",id).replace("active", id.equals("acct_second") && !ready.get() ? "pending" : "active"); }
        };
        var recipients=new RecipientAvailability("http://localhost") { @Override public void requireEnabled(UUID id) { throw new AssertionError("Pool cannot depend on personal link"); } @Override public void requirePoolMemberEnabled(UUID id) {} };
        var txRepo=proxy(TransactionRepository.class,(p,m,a)->{throw new AssertionError("No writes before eligibility");});
        var transferRepo=proxy(PoolTransferRepository.class,(p,m,a)->{throw new AssertionError("No allocations before eligibility");});
        var service=new StripeCheckoutService(txRepo,accountRepository(Map.of(owner,"acct_first",other,"acct_second")),connect,"sk_test_fixture","http://localhost/pay/success","http://localhost/pay/cancelled",1.5,25,recipients,pool,transferRepo);
        assertFalse(service.canReceivePoolTips(owner));
        assertThrows(StripeCheckoutService.PoolNotReadyException.class,()->service.createPool(owner,new TipPaymentRequest(1000L,"Danke","pool-blocked",true)));
        ready.set(true);
        assertTrue(service.canReceivePoolTips(owner));
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
