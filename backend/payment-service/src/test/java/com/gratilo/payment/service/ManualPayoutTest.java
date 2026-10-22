package com.gratilo.payment.service;

import com.gratilo.payment.domain.*;
import com.gratilo.payment.repository.ConnectedAccountRepository;
import com.gratilo.payment.api.PayoutRequest;
import com.gratilo.payment.controller.PayoutController;
import io.micronaut.security.authentication.Authentication;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ManualPayoutTest {
    final UUID user=UUID.randomUUID();
    final Store store=new Store();
    long available=10000;String schedule="manual";boolean payouts=true,bank=true;boolean timeout=false,reject=false;
    final List<String> posts=new ArrayList<>();
    StripePayoutService service(boolean simulation,boolean approved) {
        var account=new ConnectedAccountEntity();account.setStripeAccountId("acct_fixture");account.setUserId(user);
        var accounts=StripeFeeAuditTest.proxy(ConnectedAccountRepository.class,(p,m,args)->user.equals(args[0])?Optional.of(account):Optional.empty());
        var balance=new StripeBalanceService(accounts,"sk_test_fixture"){
            @Override public Optional<StripeBalanceService.Balance> balance(UUID own){assertEquals(user,own);return Optional.of(new StripeBalanceService.Balance(available,50000,"EUR",java.time.OffsetDateTime.now()));}
        };
        var connect=new StripeConnectService("sk_test_fixture","http://localhost"){
            @Override public String retrieveAccount(String id){assertEquals("acct_fixture",id);return payouts?StripeAccountFixtures.readyJson():StripeAccountFixtures.readyJson().replace("\"payouts\":{\"status\":\"active\"}","\"payouts\":{\"status\":\"pending\"}");}
        };
        return new StripePayoutService(store,accounts,balance,connect,"sk_test_fixture",simulation,approved,approved?"acct_fixture":""){
            @Override protected Map<?,?> get(String path,String account) {
                assertEquals("acct_fixture",account);
                if(path.equals("/v1/balance_settings"))return Map.of("payments",Map.of("payouts",Map.of("status","enabled","schedule",Map.of("interval",schedule))));
                if(path.contains("external_accounts"))return Map.of("data",bank?List.of(Map.of("id","ba_fixture","object","bank_account","currency","eur","default_for_currency",true,"status","verified","last4","6789","bank_name","Testbank")):List.of());
                var tx=store.values.values().iterator().next();return Map.of("data",List.of(simulated(tx,"paid")),"has_more",false);
            }
            @Override protected Map<?,?> request(String path,String form,String account,String key)throws Exception {
                assertFalse(simulation,"No POST even for a test key in simulation");assertTrue(approved);assertEquals("acct_fixture",account);
                assertEquals("/v1/payouts",path);assertTrue(form.contains("method=standard"));assertTrue(form.contains("destination=ba_fixture"));assertFalse(form.contains("application_fee"));assertEquals("gratilo-payout-"+store.values.values().iterator().next().getId(),key);
                posts.add(form);if(timeout)throw new java.net.http.HttpTimeoutException("private-provider-info");if(reject)throw new StripeRejected();
                return simulated(store.values.values().iterator().next(),"pending");
            }
        };
    }
    static class Store extends PayoutStore {
        final Map<UUID,TransactionEntity> values=new LinkedHashMap<>();
        Store(){super(null);}
        @Override public synchronized TransactionEntity existing(UUID user,UUID request){return values.values().stream().filter(t->t.getAccountUserId().equals(user)&&t.getIdempotencyKey().equals("manual-payout-"+request)).findFirst().orElse(null);}
        @Override public synchronized List<TransactionEntity> history(UUID user){return values.values().stream().filter(t->t.getAccountUserId().equals(user)).toList();}
        @Override public synchronized long reserved(UUID user){return history(user).stream().filter(t->"STRIPE_SANDBOX".equals(t.getProvider())&&t.getStatus()!=TransactionStatus.FAILED&&t.getStatus()!=TransactionStatus.CANCELLED).mapToLong(t->-t.getAmountMinor()).sum();}
        @Override public synchronized Prepared prepare(UUID user,UUID request,Long requested,long balance,String account,String bank,boolean simulation){
            var existing=existing(user,request);if(existing!=null){StripePayoutService.sameRequest(existing,requested);return new Prepared(existing,false);}
            if(history(user).stream().anyMatch(PayoutState::active))throw new IllegalStateException("active payout");
            long available=balance-(simulation?reserved(user):0),amount=requested==null?available:requested;StripePayoutService.validateAmount(amount,available);
            var tx=new TransactionEntity();tx.setId(UUID.randomUUID());tx.setAccountUserId(user);tx.setType(TransactionType.PAYOUT);tx.setStatus(TransactionStatus.PENDING);tx.setAmountMinor(-amount);tx.setProvider(simulation?"STRIPE_SANDBOX":"STRIPE");tx.setIdempotencyKey("manual-payout-"+request);
            PayoutState.metadata(tx,new LinkedHashMap<>(Map.of("payoutVersion",1,"stripeAccountId",account,"destination",bank,"simulation",simulation,"requestedAll",requested==null,"payoutStatus","submitting","gratiloPayoutFeeMinor",0)));
            values.put(tx.getId(),tx);return new Prepared(tx,true);
        }
        @Override public synchronized TransactionEntity own(UUID user,UUID id){var tx=values.get(id);if(tx==null||!tx.getAccountUserId().equals(user))throw new IllegalArgumentException("not found");return tx;}
        @Override public synchronized TransactionEntity result(UUID user,UUID id,String account,Map<?,?> data,long time){var tx=own(user,id);PayoutState.apply(tx,account,data,time);return tx;}
        @Override public synchronized TransactionEntity failed(UUID user,UUID id,boolean uncertain){var tx=own(user,id);tx.setStatus(uncertain?TransactionStatus.AUTHORIZED:TransactionStatus.FAILED);tx.setFailureCode(uncertain?"PAYOUT_OUTCOME_UNKNOWN":"PAYOUT_REJECTED");var m=PayoutState.metadata(tx);m.put("payoutStatus",uncertain?"unknown":"failed");PayoutState.metadata(tx,m);return tx;}
    }
    @Test void simulationUsesOnlyAvailableStripeBalanceAndNeverTheTipLedger()throws Exception {
        available=1234;var service=service(true,false);var tx=service.create(user,null,UUID.randomUUID());assertEquals(-1234,tx.getAmountMinor());assertEquals(TransactionStatus.PENDING,tx.getStatus());assertEquals(0,posts.size());
        assertEquals(0,service.eligibility(user).simulationAvailableMinor());assertEquals(50000,service.eligibility(user).pendingMinor());
        assertEquals(TransactionStatus.PENDING,service.reconcile(user,tx.getId()).getStatus());assertEquals(TransactionStatus.BOOKED,service.reconcile(user,tx.getId()).getStatus());
        assertEquals(0,service.eligibility(user).simulationAvailableMinor());
    }
    @Test void personalPoolAndMixedBalancesUseExactlyTheSameConnectedAccount()throws Exception {
        for(long balance:new long[]{5000,4750,9750}){available=balance;store.values.clear();var tx=service(true,false).create(user,null,UUID.randomUUID());assertEquals(-balance,tx.getAmountMinor());assertEquals("acct_fixture",PayoutState.metadata(tx).get("stripeAccountId"));}
    }
    @Test void partialPayoutAndRepeatedSameRequestHaveOneFrozenAmountAndNoExtraFee()throws Exception {
        var s=service(true,false);var key=UUID.randomUUID();var a=s.create(user,2500L,key);assertEquals(-2500,a.getAmountMinor());assertEquals(a.getId(),s.create(user,2500L,key).getId());assertEquals(1,store.values.size());assertEquals(0,PayoutState.metadata(a).get("gratiloPayoutFeeMinor"));assertThrows(IllegalArgumentException.class,()->s.create(user,2600L,key));assertThrows(IllegalArgumentException.class,()->s.create(user,null,key));
    }
    @Test void unavailablePendingNegativeAndExcessiveAmountsCannotBePaid()throws Exception {
        var s=service(true,false);for(long amount:new long[]{-1,0,99,10001,Long.MAX_VALUE})assertThrows(IllegalArgumentException.class,()->s.create(user,amount,UUID.randomUUID()));assertTrue(store.values.isEmpty());available=0;assertFalse(s.eligibility(user).eligible());assertThrows(IllegalArgumentException.class,()->s.create(user,null,UUID.randomUUID()));available=-100;assertThrows(IllegalArgumentException.class,()->s.create(user,null,UUID.randomUUID()));
    }
    @Test void accountReadinessBankAndManualScheduleAreMandatory() {
        var s=service(true,false);bank=false;assertThrows(IllegalStateException.class,()->s.eligibility(user));bank=true;schedule="weekly";assertThrows(IllegalStateException.class,()->s.eligibility(user));schedule="manual";payouts=false;assertThrows(IllegalStateException.class,()->s.create(user,100L,UUID.randomUUID()));assertTrue(store.values.isEmpty());
    }
    @Test void unapprovedExternalModeIsBlockedBeforeCreatingAnIntent()throws Exception {var s=service(false,false);assertFalse(s.eligibility(user).eligible());assertThrows(IllegalStateException.class,()->s.create(user,100L,UUID.randomUUID()));assertTrue(store.values.isEmpty());}
    @Test void timeoutIsPersistedAndCannotCreateAnotherPayoutEvenAfterRepeatedRequests()throws Exception {
        timeout=true;var s=service(false,true);var key=UUID.randomUUID();var tx=s.create(user,2500L,key);assertEquals(TransactionStatus.AUTHORIZED,tx.getStatus());assertEquals(1,posts.size());s.create(user,2500L,key);assertEquals(1,posts.size());assertThrows(IllegalStateException.class,()->s.create(user,2500L,UUID.randomUUID()));assertEquals(TransactionStatus.BOOKED,s.reconcile(user,tx.getId()).getStatus());assertEquals(1,posts.size());
    }
    @Test void definitiveStripeRejectionIsFailedNotPaid()throws Exception {reject=true;var tx=service(false,true).create(user,500L,UUID.randomUUID());assertEquals(TransactionStatus.FAILED,tx.getStatus());assertEquals(1,posts.size());}
    @Test void duplicateClicksAndConcurrentDifferentIdsDoNotCreateTwoPayouts()throws Exception {
        var s=service(true,false);var key=UUID.randomUUID();var executor=Executors.newFixedThreadPool(2);
        try{var tasks=executor.invokeAll(List.<Callable<TransactionEntity>>of(()->s.create(user,1000L,key),()->s.create(user,1000L,key)));assertEquals(tasks.get(0).get().getId(),tasks.get(1).get().getId());assertEquals(1,store.values.size());assertThrows(IllegalStateException.class,()->s.create(user,1000L,UUID.randomUUID()));}finally{executor.shutdownNow();}
    }
    @Test void eventsBindAccountPayoutAmountAndCurrencyAndNeverUndoPaidWithOldEvents()throws Exception {
        var tx=service(true,false).create(user,1000L,UUID.randomUUID());var pending=StripePayoutService.simulated(tx,"pending");var paid=StripePayoutService.simulated(tx,"paid");assertFalse(PayoutState.apply(tx,"acct_other",paid,10));assertEquals(TransactionStatus.PENDING,tx.getStatus());assertTrue(PayoutState.apply(tx,"acct_fixture",paid,20));assertFalse(PayoutState.apply(tx,"acct_fixture",pending,10));assertFalse(PayoutState.apply(tx,"acct_fixture",pending,30));assertTrue(PayoutState.apply(tx,"acct_fixture",paid,20));assertEquals(TransactionStatus.BOOKED,tx.getStatus());
        var bad=new LinkedHashMap<>(paid);bad.put("amount",999);assertFalse(PayoutState.apply(tx,"acct_fixture",bad,30));bad.put("amount",1000);bad.put("currency","usd");assertFalse(PayoutState.apply(tx,"acct_fixture",bad,30));bad.put("currency","eur");bad.put("id","po_other");assertFalse(PayoutState.apply(tx,"acct_fixture",bad,30));
        assertFalse(PayoutState.apply(tx,"acct_fixture",StripePayoutService.simulated(tx,"failed"),19));assertTrue(PayoutState.apply(tx,"acct_fixture",StripePayoutService.simulated(tx,"failed"),31));assertEquals(TransactionStatus.FAILED,tx.getStatus());assertFalse(PayoutState.apply(tx,"acct_fixture",paid,40));
    }
    @Test void anotherEmployeeCannotSeeOrReconcileAnOwnPayout()throws Exception {var s=service(true,false);var tx=s.create(user,1000L,UUID.randomUUID());assertTrue(s.history(UUID.randomUUID()).isEmpty());assertThrows(IllegalArgumentException.class,()->s.reconcile(UUID.randomUUID(),tx.getId()));}
    @Test void actualHttpRequestUsesFrozenAccountStableVersionAndIdempotencyAndSimulationBlocksPosts() {
        var s=service(false,true);var request=s.httpRequest("/v1/payouts","amount=100&currency=eur&method=standard","acct_fixture","frozen-key");assertEquals("acct_fixture",request.headers().firstValue("Stripe-Account").orElseThrow());assertEquals(StripeConnectService.STABLE_VERSION,request.headers().firstValue("Stripe-Version").orElseThrow());assertEquals("frozen-key",request.headers().firstValue("Idempotency-Key").orElseThrow());assertEquals("POST",request.method());assertThrows(IllegalStateException.class,()->service(true,true).httpRequest("/v1/payouts","amount=100","acct_fixture","same"));assertThrows(IllegalStateException.class,()->s.httpRequest("/v1/payouts","amount=100","acct_other","same"));
    }
    @Test void controllerRequiresRequestIdAndUsesAuthenticatedUserOnly()throws Exception {
        var controller=new PayoutController(service(true,false));var auth=Authentication.build(user.toString());assertEquals(400,controller.create(auth,new PayoutRequest(100L,null)).code());assertTrue(store.values.isEmpty());var key=UUID.randomUUID();assertEquals(200,controller.create(auth,new PayoutRequest(100L,key)).code());assertEquals(user,store.values.values().iterator().next().getAccountUserId());assertEquals(1,store.values.size());

    }
}
