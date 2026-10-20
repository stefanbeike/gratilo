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
    @Inject UserRepository users;
    @Inject PoolRepository pools;
    @Inject PoolMemberRepository members;
    @Override public Map<String,String> getProperties(){
        String url=System.getenv("REGISTRATION_TEST_DATABASE_URL");
        if(url!=null&&!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/gratilo_registration_test"))throw new IllegalStateException("Only an isolated loopback test database is allowed");
        return Map.of("features.tip-pools-enabled","true","datasources.default.url",url==null?"jdbc:h2:mem:teamguard;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS user_mgmt":url);
    }
    @MockBean(EmailSendService.class) EmailSendService mail(){return mock(EmailSendService.class);}
    final List<UUID> createdUsers=new ArrayList<>();
    UUID user(){var u=new UserEntity();u.setEmail(UUID.randomUUID()+"@example.invalid");u.setPasswordHash("unused-test-only");u=users.save(u);createdUsers.add(u.getId());return u.getId();}
    @AfterEach void cleanup(){for(var id:createdUsers){for(var m:members.findByUserId(id))members.deleteById(m.getId());pools.findByOwnerId(id).ifPresent(p->pools.deleteById(p.getId()));}for(var id:createdUsers)users.deleteById(id);createdUsers.clear();}
    void race(Runnable first,Runnable second) throws Exception {
        var start=new CountDownLatch(1);var executor=Executors.newFixedThreadPool(2);
        try {
            var futures=List.of(executor.submit(()->attempt(start,first)),executor.submit(()->attempt(start,second)));start.countDown();
            var outcomes=new ArrayList<Boolean>();for(var future:futures)outcomes.add(future.get(30,TimeUnit.SECONDS));
            assertEquals(1,outcomes.stream().filter(Boolean::booleanValue).count(),"Exactly one concurrent membership change may succeed");
        } finally {executor.shutdownNow();}
    }
    boolean attempt(CountDownLatch start,Runnable action){try{start.await();action.run();return true;}catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("Team")||expected.getMessage().contains("Pool"));return false;}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}}
    @Test void parallelTeamCreationCannotCreateTwoActiveTeams() throws Exception{
        UUID user=user();race(()->service.create(user,"First"),()->service.create(user,"Second"));
        assertEquals(1,members.findByUserId(user).stream().filter(m->m.isActive()&&"ACTIVE".equals(m.getStatus())).count());assertTrue(pools.findByOwnerId(user).isPresent());
    }
    @Test void acceptingAnInvitationAndCreatingATeamCannotBothSucceed() throws Exception{
        UUID owner=user(),member=user();var p=service.create(owner,"Existing");
        var invited=service.invite(owner,p.id(),users.findById(member).orElseThrow().getEmail()).members().stream().filter(m->m.userId().equals(member)).findFirst().orElseThrow();
        race(()->service.respond(invited.invitationToken(),member,true),()->service.create(member,"New"));
        assertEquals(1,members.findByUserId(member).stream().filter(m->m.isActive()&&"ACTIVE".equals(m.getStatus())).count());
    }
}
