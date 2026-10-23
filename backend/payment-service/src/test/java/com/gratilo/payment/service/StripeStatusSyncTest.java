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
        body.set(null);assertFalse(sync.refreshAccount("acct_fixture"));assertFalse(row.isTransfersEnabled());assertEquals("UNAVAILABLE",row.getStripeStatus());
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
