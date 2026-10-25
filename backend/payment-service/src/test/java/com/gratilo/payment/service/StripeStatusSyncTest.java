package com.gratilo.payment.service;
import com.gratilo.payment.domain.ConnectedAccountEntity;
import com.gratilo.payment.repository.ConnectedAccountRepository;
import com.gratilo.payment.controller.*;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.http.HttpStatus;
import java.lang.reflect.Proxy;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class StripeStatusSyncTest {
    @Test void refreshesAndPersistsCurrentPermissionsAndFailsClosedOnNetworkErrors() {
        var row=new ConnectedAccountEntity(); row.setUserId(UUID.randomUUID());row.setStripeAccountId("acct_fixture");
        var body=new java.util.concurrent.atomic.AtomicReference<String>(StripeAccountFixtures.readyJson());
        var repo=(ConnectedAccountRepository)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{ConnectedAccountRepository.class},(p,m,a)->switch(m.getName()) {
            case "findByUserId","findByStripeAccountId" -> Optional.of(row);
            case "update" -> a[0]; default -> throw new AssertionError(m.getName());
        });
        var stripe=new StripeConnectService("sk_test_fixture","http://localhost") { @Override public String retrieveAccount(String id) {if(body.get()==null)throw new IllegalStateException("offline");return body.get();} };
        var sync=new StripeStatusSync(repo,stripe);
        int[] preparations={0};sync.payouts=new StripePayoutService(null,null,null,null,"sk_test_fixture",true,false,""){
            @Override public boolean automaticPreparationEnabled(){return true;}
            @Override public StripePayoutSetup.Result prepareAccount(UUID user){assertEquals(row.getUserId(),user);preparations[0]++;return null;}
        };
        assertEquals("READY",sync.refresh(row.getUserId()).status());assertTrue(row.isTransfersEnabled()); assertNotNull(row.getStatusCheckedAt());assertEquals(0,preparations[0]);
        body.set(StripeAccountFixtures.readyJson().replace("active","restricted"));
        assertTrue(sync.refreshAccount("acct_fixture"));assertEquals(1,preparations[0]);assertFalse(row.isTransfersEnabled()); assertEquals("ACTION_REQUIRED",row.getStripeStatus());
        String confirmedStatus=row.getStripeStatus();var confirmedCheckedAt=row.getStatusCheckedAt();
        body.set(null);assertFalse(sync.refreshAccount("acct_fixture"));assertFalse(row.isTransfersEnabled());
        assertEquals(confirmedStatus,row.getStripeStatus());assertEquals(confirmedCheckedAt,row.getStatusCheckedAt());
        var unavailable=sync.refresh(row.getUserId());assertEquals("UNAVAILABLE",unavailable.status());assertNull(unavailable.transfersEnabled());assertNull(unavailable.payoutsEnabled());assertEquals(confirmedCheckedAt,unavailable.checkedAt());
        assertEquals(confirmedStatus,row.getStripeStatus());assertEquals(confirmedCheckedAt,row.getStatusCheckedAt());
    }
    @Test void incompleteOrWrongModeV2ResponseDoesNotOverwriteLastConfirmedReadiness() {
        var row=new ConnectedAccountEntity();row.setUserId(UUID.randomUUID());row.setStripeAccountId("acct_fixture");row.setTransfersEnabled(true);row.setStripeStatus("READY");var checked=java.time.OffsetDateTime.now().minusSeconds(10);row.setStatusCheckedAt(checked);
        int[] updates={0};var repo=(ConnectedAccountRepository)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{ConnectedAccountRepository.class},(p,m,a)->switch(m.getName()){
            case "findByUserId"->Optional.of(row);case "update"->{updates[0]++;yield a[0];}default->throw new AssertionError(m.getName());});
        var stripe=new StripeConnectService("sk_test_fixture","http://localhost"){@Override public String retrieveAccount(String id){return StripeAccountFixtures.readyJson().replace("\"livemode\":false","\"livemode\":true");}};
        var status=new StripeStatusSync(repo,stripe).refresh(row.getUserId());
        assertEquals("UNAVAILABLE",status.status());assertNull(status.transfersEnabled());assertEquals(checked,status.checkedAt());assertEquals(0,updates[0]);assertTrue(row.isTransfersEnabled());assertEquals("READY",row.getStripeStatus());
    }
    @Test void ownerCannotInspectAnotherOwnersMembers() {
        UUID poolId=UUID.randomUUID();
        var client=new PoolDistributionClient("http://localhost","test",true){
            @Override public Optional<Distribution> find(UUID id){return Optional.of(new Distribution(poolId,"Pool",List.of(),UUID.randomUUID()));}
        };
        var controller=new PoolMemberStripeStatusController(client,null);
        assertEquals(HttpStatus.FORBIDDEN,controller.get(poolId,Authentication.build(UUID.randomUUID().toString())).getStatus());
    }
    @Test void poolMemberStripeStatusLookupUsesPoolIdEvenWhenOwnerHasSeveralPools() {
        UUID owner=UUID.randomUUID(),poolId=UUID.randomUUID();
        var queried=new java.util.concurrent.atomic.AtomicReference<UUID>();
        var client=new PoolDistributionClient("http://localhost","test",true){
            @Override public Optional<Distribution> find(UUID id){queried.set(id);return Optional.of(new Distribution(poolId,"Pool",List.of(),owner));}
        };
        var stripe=new StripeStatusSync(null,null){
            @Override public Status refresh(UUID user){return new Status(user,"READY","COMPLETE",true,false,java.time.OffsetDateTime.now());}
        };
        var controller=new PoolMemberStripeStatusController(client,stripe);
        var response=controller.get(poolId,Authentication.build(owner.toString()));
        assertEquals(HttpStatus.OK,response.getStatus());
        assertEquals(poolId,queried.get());
    }
}
