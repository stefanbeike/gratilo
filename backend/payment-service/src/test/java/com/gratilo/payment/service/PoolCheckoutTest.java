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
    @Test void configuredGrossUpIsSharedByPoolCheckoutsAtTenAndTwentyFiveEuros() throws Exception {
        for(long tip:new long[]{1000,2500})for(String rate:List.of("1.5","2.8","3.15"))for(boolean covered:new boolean[]{true,false})
            assertPoolCharge(covered,tip,new java.math.BigDecimal(rate));
    }
    private void assertPoolCharge(boolean covered) throws Exception { assertPoolCharge(covered,501,new java.math.BigDecimal("1.5")); }
    @Test void threeUnequalSharesUseTheNetBudgetOnceAndPreserveCents() throws Exception {
        assertPoolCharge(false,2500,new java.math.BigDecimal("3.15"),List.of(20.0,30.0,50.0));
        assertPoolCharge(true,2500,new java.math.BigDecimal("3.15"),List.of(20.0,30.0,50.0));
    }
    @Test void twentyFiveEurosDeductsPercentageCommissionOnceBeforeSplitting() throws Exception {
        assertPoolCharge(false,2500,new java.math.BigDecimal("3.15"));
    }
    @Test void tinySharesAreRejectedBeforeCheckoutOrAllocationWrites() {
        assertThrows(TipPricing.NoDistributableTipException.class,()->assertPoolCharge(false,100,new java.math.BigDecimal("3.15"),List.of(99.99,0.01)));
    }
    private void assertPoolCharge(boolean covered,long tip,java.math.BigDecimal rate) throws Exception {
        assertPoolCharge(covered,tip,rate,List.of(50.0,50.0));
    }
    private void assertPoolCharge(boolean covered,long tip,java.math.BigDecimal rate,List<Double> distribution) throws Exception {
        var expected=TipPricing.poolQuote(tip,covered,rate,25,TipPricing.DEFAULT_PLATFORM_PERCENT);
        UUID owner = UUID.randomUUID(), first = new UUID(0,1), second = new UUID(0,2), third = new UUID(0,3);
        var members=new ArrayList<PoolDistributionClient.Member>();
        members.add(new PoolDistributionClient.Member(first,"A",distribution.get(0),true));
        members.add(new PoolDistributionClient.Member(second,"B",distribution.get(1),true));
        if(distribution.size()==3)members.add(new PoolDistributionClient.Member(third,"C",distribution.get(2),true));
        UUID frozenPoolId=UUID.randomUUID();
        var poolClient = new PoolDistributionClient("http://localhost", "fixture", true) {
            @Override public Optional<Distribution> find(UUID id) { return Optional.of(new Distribution(frozenPoolId, "Team",
                    members)); }
        };
        var accounts = accountRepository(Map.of(first, "acct_first", second, "acct_second",third,"acct_third"));
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
                rate, 25, recipients, poolClient, transferRepo, feeAudit) {
            @Override protected String stripeRequest(String path, String form, String key) {
                calls.add(path + " " + Objects.toString(form, ""));
                return "{\"livemode\":false,\"id\":\"cs_test_pool\",\"url\":\"https://checkout.stripe.com/test\"}";
            }
        };
        assertThrows(StripeCheckoutService.PoolNotReadyException.class,()->service.createPool(owner,
                new TipPaymentRequest(tip,"Team danke","wrong-target",covered,null,null,UUID.randomUUID())));
        assertTrue(calls.isEmpty());assertNull(stored.get());assertTrue(snapshots.isEmpty());
        var response = service.createPool(owner, new TipPaymentRequest(tip, "Team danke", "pool-key", covered,null,null,frozenPoolId)).orElseThrow();
        assertThrows(StripeCheckoutService.CheckoutConflictException.class,()->service.createPool(owner,
                new TipPaymentRequest(tip,"Team danke","pool-key",covered,null,null,UUID.randomUUID())));
        assertEquals("https://checkout.stripe.com/test", response.checkoutUrl());
        var form = calls.getFirst();
        assertTrue(form.contains("line_items%5B0%5D%5Bprice_data%5D%5Bunit_amount%5D="+expected.totalAmountMinor()));
        assertTrue(form.contains("payment_intent_data%5Btransfer_group%5D=gratilo_"));
        assertFalse(form.contains("transfer_data%5Bdestination%5D"));
        assertFalse(form.contains("application_fee_amount"));
        assertFalse(form.contains("payment_method_types"));
        service.createPool(owner,new TipPaymentRequest(tip,"Team danke","pool-key",covered));
        assertEquals(1,calls.stream().filter(c->c.contains("unit_amount")).count());
        assertEquals(distribution.size(), snapshots.size());
        assertEquals(expected,response.quote());
        assertEquals(expected.recipientAmountMinor(),TipPricingSnapshot.from(stored.get()).recipientMinor());
        if(tip==2500&&rate.toString().equals("3.15")) {
            var actual=snapshots.stream().sorted(java.util.Comparator.comparing(a->a.getRecipientUserId().toString())).map(PoolTransferEntity::getAmountMinor).toList();
            assertEquals(distribution.size()==3?(covered?List.of(475L,713L,1187L):List.of(454L,681L,1136L)):(covered?List.of(1188L,1187L):List.of(1136L,1135L)),actual);
        }
        assertEquals(1,auditRows.size());var audit=auditRows.get(stored.get().getId());
        assertEquals(expected.totalAmountMinor(),audit.getChargedMinor());assertEquals(expected.serviceFeeMinor(),audit.getSurchargeMinor());
        assertEquals("Team danke",stored.get().getDescription());
        assertTrue(snapshots.stream().allMatch(PoolTransferEntity::isSettlementApproved));
        assertEquals(expected.recipientAmountMinor(), snapshots.stream().mapToLong(PoolTransferEntity::getAmountMinor).sum());
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
        var txRepo=proxy(TransactionRepository.class,(p,m,a)->{if(m.getName().equals("findByAccountUserIdAndIdempotencyKey"))return Optional.empty();throw new AssertionError("No writes before eligibility");});
        var transferRepo=proxy(PoolTransferRepository.class,(p,m,a)->{throw new AssertionError("No allocations before eligibility");});
        var service=new StripeCheckoutService(txRepo,accountRepository(Map.of(owner,"acct_first",other,"acct_second")),connect,"sk_test_fixture","http://localhost/pay/success","http://localhost/pay/cancelled",new java.math.BigDecimal("1.5"),25,recipients,pool,transferRepo);
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
