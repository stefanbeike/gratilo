package com.gratilo.user.service;

import com.gratilo.user.domain.UserEntity;
import com.gratilo.user.repository.*;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Real transactions and user-row locking; confined to H2 or disposable loopback PostgreSQL. */
@MicronautTest(environments="registration-test",transactional=false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TeamMembershipIntegrationTest implements TestPropertyProvider {
    @Inject PoolService service;
    @Inject com.gratilo.user.controller.UserController userController;
    @Inject PoolDissolutionState dissolution;
    @Inject PoolDissolutionNoticeRepository notices;
    @Inject UserRepository users;
    @Inject PoolRepository pools;
    @Inject PoolMemberRepository members;
    @Inject PaymentUrlRepository urls;
    @Inject InventoryTestReset inventoryReset;
    void clearInventory() {inventoryReset.truncate();}
    @BeforeEach void inventory() throws Exception {clearInventory();for(long id=1;id<=4;id++){
        var slot=new com.gratilo.user.domain.PaymentUrlEntity();slot.setId(id);slot.setPublicPath("team-fixture-"+id);slot.setPaymentUrl("http://localhost:3000/pay/team-fixture-"+id);urls.save(slot);
    }}
    @Override public Map<String,String> getProperties(){
        String url=System.getenv("REGISTRATION_TEST_DATABASE_URL");
        if(url!=null&&!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/gratilo_registration_test"))throw new IllegalStateException("Only an isolated loopback test database is allowed");
        return Map.of("features.tip-pools-enabled","true","datasources.default.url",url==null?"jdbc:h2:mem:teamguard;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS user_mgmt":url);
    }
    @MockBean(EmailSendService.class) EmailSendService mail(){return mock(EmailSendService.class);}
    final List<UUID> createdUsers=new ArrayList<>();
    UUID user(){var u=new UserEntity();u.setEmail(UUID.randomUUID()+"@example.invalid");u.setPasswordHash("unused-test-only");u=users.save(u);createdUsers.add(u.getId());return u.getId();}
    @AfterEach void cleanup() throws Exception {
        notices.deleteAll();
        // The production FK deliberately rejects cascade deletion. Remove every fixture
        // membership first, including members whose users follow the pool owner in this list.
        for(var id:createdUsers)for(var member:members.findByUserId(id))members.deleteById(member.getId());
        for(var id:createdUsers)for(var pool:pools.findAllByOwnerIdOrderByCreatedAtAsc(id))pools.deleteById(pool.getId());
        clearInventory();
        for(var id:createdUsers)users.deleteById(id);
        createdUsers.clear();
    }
    void concurrently(Runnable first,Runnable second) throws Exception {
        var start=new CountDownLatch(1);var executor=Executors.newFixedThreadPool(2);
        try {
            var futures=List.of(executor.submit(()->attempt(start,first)),executor.submit(()->attempt(start,second)));start.countDown();
            for(var future:futures)assertTrue(future.get(30,TimeUnit.SECONDS),"Independent pool operations must both succeed");
        } finally {executor.shutdownNow();}
    }
    boolean attempt(CountDownLatch start,Runnable action){try{start.await();action.run();return true;}catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("Team")||expected.getMessage().contains("Pool"));return false;}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}}
    @Test void aUserCanOwnMultiplePoolsAndStayMemberOfOtherPools() throws Exception{
        UUID ownerA=user(),ownerC=user(),member=user();var first=service.create(ownerA,"First");var third=service.create(ownerC,"Third");
        var inviteA=service.invite(ownerA,first.id(),users.findById(member).orElseThrow().getEmail()).members().stream().filter(m->m.userId().equals(member)).findFirst().orElseThrow();
        var inviteC=service.invite(ownerC,third.id(),users.findById(member).orElseThrow().getEmail()).members().stream().filter(m->m.userId().equals(member)).findFirst().orElseThrow();
        service.respond(inviteA.invitationToken(),member,true);service.respond(inviteC.invitationToken(),member,true);
        var second=service.create(member,"Second");var fourth=legacyPool(member,"Fourth");
        assertEquals(2,pools.findAllByOwnerIdOrderByCreatedAtAsc(member).size());
        assertEquals(4,members.findByUserId(member).stream().filter(m->m.isActive()&&"ACTIVE".equals(m.getStatus())).count());
        assertTrue(service.allPools(member).stream().anyMatch(p->p.id().equals(first.id())));
        assertTrue(service.allPools(member).stream().anyMatch(p->p.id().equals(third.id())));
        assertTrue(service.allPools(member).stream().anyMatch(p->p.id().equals(second.id())));
        assertTrue(service.allPools(member).stream().anyMatch(p->p.id().equals(fourth.id())));
    }
    @Test void twoOwnPoolsAndThreeForeignMembershipsRemainIndependentWhenLeavingOne(){
        UUID user=user();var ownA=service.create(user,"Own A");var ownB=legacyPool(user,"Own B");
        var foreign=new ArrayList<UUID>();
        for(int i=0;i<3;i++){
            UUID owner=user();var pool=service.create(owner,"Foreign "+i);foreign.add(pool.id());
            var invitation=service.invite(owner,pool.id(),users.findById(user).orElseThrow().getEmail()).members().stream().filter(m->m.userId().equals(user)).findFirst().orElseThrow();
            service.respond(invitation.invitationToken(),user,true);
        }
        assertEquals(2,pools.findAllByOwnerIdOrderByCreatedAtAsc(user).size());
        assertEquals(3,service.memberships(user).size());assertEquals(5,service.allPools(user).size());
        assertEquals(5,service.allPools(user).stream().map(p->p.paymentUrl()).distinct().count());
        UUID leaving=members.findByPoolIdAndUserId(foreign.get(1),user).orElseThrow().getId();
        service.removeMember(user,leaving);
        assertEquals(Set.of(foreign.get(0),foreign.get(2)),new HashSet<>(service.memberships(user).stream().map(p->p.id()).toList()));
        assertEquals(4,service.allPools(user).size());assertNotNull(service.details(user,ownA.id()));assertNotNull(service.details(user,ownB.id()));
        assertThrows(PoolService.PoolPermissionException.class,()->service.details(user,foreign.get(1)));
    }
    @Test void acceptingAnInvitationAndCreatingOwnPoolCanHappenConcurrently() throws Exception{
        UUID owner=user(),member=user();var p=service.create(owner,"Existing");
        var invited=service.invite(owner,p.id(),users.findById(member).orElseThrow().getEmail()).members().stream().filter(m->m.userId().equals(member)).findFirst().orElseThrow();
        concurrently(()->service.respond(invited.invitationToken(),member,true),()->service.create(member,"New"));
        assertEquals(2,members.findByUserId(member).stream().filter(m->m.isActive()&&"ACTIVE".equals(m.getStatus())).count());
        assertEquals(1,pools.findAllByOwnerIdOrderByCreatedAtAsc(member).size());
    }
    @Test void dissolutionEndsOnlyTargetMembershipsAndIsIdempotent(){
        UUID owner=user(),other=user(),participant=user(),invited=user();
        var target=service.create(owner,"Auflösen");
        var otherPools=new ArrayList<UUID>();
        for(int i=0;i<2;i++){
            var p=legacyPool(other,"Bleibt "+i);otherPools.add(p.id());
            var m=service.invite(other,p.id(),users.findById(participant).orElseThrow().getEmail()).members().stream().filter(x->x.userId().equals(participant)).findFirst().orElseThrow();
            service.respond(m.invitationToken(),participant,true);
        }
        var m=service.invite(owner,target.id(),users.findById(participant).orElseThrow().getEmail()).members().stream().filter(x->x.userId().equals(participant)).findFirst().orElseThrow();
        service.respond(m.invitationToken(),participant,true);
        var invite=service.invite(owner,target.id(),users.findById(invited).orElseThrow().getEmail()).members().stream().filter(x->x.userId().equals(invited)).findFirst().orElseThrow();
        assertEquals(3,service.memberships(participant).size());
        var before=users.findById(participant).orElseThrow();boolean enabled=before.isPaymentEnabled();String personal=before.getPaymentUrlPath();
        assertThrows(PoolService.PoolPermissionException.class,()->dissolution.begin(participant,target.id(),"Auflösen"));
        assertThrows(PoolService.PoolPermissionException.class,()->dissolution.begin(other,target.id(),"Auflösen"));
        assertThrows(PoolDissolutionState.Failure.class,()->dissolution.begin(owner,target.id(),"wrong"));
        assertTrue(dissolution.begin(owner,target.id(),"Auflösen"));
        assertNull(service.distribution(target.id()));assertNull(service.publicLink("team-"+target.id().toString().replace("-","")));
        assertThrows(IllegalArgumentException.class,()->service.invite(owner,target.id(),users.findById(invited).orElseThrow().getEmail()));
        assertThrows(IllegalArgumentException.class,()->service.respond(invite.invitationToken(),invited,true));
        dissolution.complete(owner,target.id());
        var deleted=pools.findById(target.id()).orElseThrow();assertTrue(deleted.isDeleted());assertNotNull(deleted.getDeletedAt());
        assertTrue(members.findByPoolId(target.id()).stream().allMatch(x->!x.isActive()&&"ENDED".equals(x.getStatus())&&x.getInvitationToken()==null));
        assertEquals(3,notices.findByPoolId(target.id()).size());
        assertEquals(Set.of(owner,participant,invited),notices.findByPoolId(target.id()).stream().map(x->x.getUserId()).collect(java.util.stream.Collectors.toSet()));
        assertFalse(dissolution.begin(owner,target.id(),"Auflösen"));dissolution.complete(owner,target.id());assertEquals(3,notices.findByPoolId(target.id()).size());
        assertEquals(2,service.memberships(participant).size());assertEquals(new HashSet<>(otherPools),service.memberships(participant).stream().map(x->x.id()).collect(java.util.stream.Collectors.toSet()));
        assertTrue(service.allPools(owner).stream().noneMatch(x->x.id().equals(target.id())));
        var after=users.findById(participant).orElseThrow();assertEquals(enabled,after.isPaymentEnabled());assertEquals(personal,after.getPaymentUrlPath());
        assertNotNull(service.create(owner,"Nachfolger"));assertNull(service.distribution(target.id()));assertNull(service.distribution(owner));
    }
    @Test void paymentBlockAndOutageKeepRecoverableClosingStateWithoutMail(){
        UUID owner=user();var target=service.create(owner,"Offen");
        var payments=org.mockito.Mockito.mock(PoolPaymentClosureClient.class);
        var coordinator=new PoolDissolutionService(dissolution,payments);
        try{org.mockito.Mockito.when(payments.freeze(target.id())).thenReturn(false);}catch(Exception e){throw new AssertionError(e);}
        var blocked=assertThrows(PoolDissolutionState.Failure.class,()->coordinator.dissolve(owner,target.id(),"Offen"));assertEquals(409,blocked.status);
        assertEquals("CLOSING",pools.findById(target.id()).orElseThrow().getStatus());assertEquals(0,notices.findByPoolId(target.id()).size());
        try{org.mockito.Mockito.when(payments.freeze(target.id())).thenThrow(new java.io.IOException());}catch(Exception e){throw new AssertionError(e);}
        assertEquals(503,assertThrows(PoolDissolutionState.Failure.class,()->coordinator.dissolve(owner,target.id(),"Offen")).status);
        try{org.mockito.Mockito.doReturn(true).when(payments).freeze(target.id());}catch(Exception e){throw new AssertionError(e);}
        coordinator.dissolve(owner,target.id(),"Offen");assertTrue(pools.findById(target.id()).orElseThrow().isDeleted());
        coordinator.dissolve(owner,target.id(),"Offen");assertEquals(1,notices.findByPoolId(target.id()).size());
    }
    @Test void accountDeletionCannotBypassFinancialDissolution(){
        UUID owner=user();var target=service.create(owner,"Sicher");
        assertThrows(IllegalArgumentException.class,()->service.removeUser(owner));assertFalse(pools.findById(target.id()).orElseThrow().isDeleted());
        assertEquals(io.micronaut.http.HttpStatus.CONFLICT,userController.deleteAccount(io.micronaut.security.authentication.Authentication.build(owner.toString())).getStatus());
        assertNull(users.findById(owner).orElseThrow().getDeletedAt());
    }
    // Existing multiple owned pools are grandfathered: fixture represents pre-migration data.
    com.gratilo.user.api.PoolResponses.Pool legacyPool(UUID owner,String name){
        var p=new com.gratilo.user.domain.PoolEntity();p.setId(UUID.randomUUID());p.setOwnerId(owner);p.setName(name);p.setPublicPath("team-"+p.getId().toString().replace("-",""));p.setCreatedAt(java.time.OffsetDateTime.now());pools.save(p);
        var m=new com.gratilo.user.domain.PoolMemberEntity();m.setId(UUID.randomUUID());m.setPoolId(p.getId());m.setUserId(owner);m.setStatus("ACTIVE");m.setActive(true);m.setSharePercent(100);m.setCreatedAt(java.time.OffsetDateTime.now());members.save(m);
        return service.details(owner,p.getId());
    }
    @Test void secondPoolCreationIsSerializedAndClosingStillCounts(){
        UUID owner=user();var first=service.create(owner,"Einer");assertThrows(IllegalArgumentException.class,()->service.create(owner,"Zweiter"));
        dissolution.begin(owner,first.id(),"Einer");assertThrows(IllegalArgumentException.class,()->service.create(owner,"Zweiter"));
        dissolution.complete(owner,first.id());assertNotNull(service.create(owner,"Nachfolger"));assertNull(service.distribution(first.id()));assertNull(service.distribution(owner));
    }
    @Test void authenticatedControllerRejectsMemberAndForeignOwnerBeforeCallingPayments()throws Exception{
        UUID owner=user(),foreign=user();var target=service.create(owner,"Authentifiziert");
        var payments=org.mockito.Mockito.mock(PoolPaymentClosureClient.class);org.mockito.Mockito.when(payments.freeze(target.id())).thenReturn(true);
        var controller=new com.gratilo.user.controller.PoolDissolutionController(new PoolDissolutionService(dissolution,payments));
        var request=new com.gratilo.user.controller.PoolDissolutionController.Confirmation("Authentifiziert");
        var invite=service.invite(owner,target.id(),users.findById(foreign).orElseThrow().getEmail()).members().stream().filter(m->m.userId().equals(foreign)).findFirst().orElseThrow();service.respond(invite.invitationToken(),foreign,true);
        assertEquals(io.micronaut.http.HttpStatus.FORBIDDEN,controller.dissolve(target.id(),request,io.micronaut.security.authentication.Authentication.build(foreign.toString())).getStatus());
        org.mockito.Mockito.verifyNoInteractions(payments);
        assertEquals(io.micronaut.http.HttpStatus.NOT_FOUND,controller.dissolve(UUID.randomUUID(),request,io.micronaut.security.authentication.Authentication.build(owner.toString())).getStatus());
        var auth=io.micronaut.security.authentication.Authentication.build(owner.toString());
        assertEquals(io.micronaut.http.HttpStatus.OK,controller.dissolve(target.id(),request,auth).getStatus());
        assertEquals(io.micronaut.http.HttpStatus.OK,controller.dissolve(target.id(),request,auth).getStatus());org.mockito.Mockito.verify(payments,org.mockito.Mockito.times(1)).freeze(target.id());
    }
    @Test void concurrentCreationAllowsOnlyOneOwnPool()throws Exception{
        UUID owner=user();var start=new java.util.concurrent.CountDownLatch(1);
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)){
            java.util.concurrent.Callable<Boolean> create=()->{start.await();try{service.create(owner,"Parallel");return true;}catch(IllegalArgumentException blocked){return false;}};
            var a=executor.submit(create);var b=executor.submit(create);start.countDown();assertNotEquals(a.get(),b.get());assertEquals(1,pools.findAllByOwnerIdOrderByCreatedAtAsc(owner).size());
        }
    }
}
