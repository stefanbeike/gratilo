package com.gratilo.user.controller;

import com.gratilo.user.api.UserResponses;
import com.gratilo.user.domain.*;
import com.gratilo.user.repository.*;
import com.gratilo.user.service.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.junit.jupiter.api.Assertions.*;

class PersonalPoolLinkTest {
    @Test void ownershipAndDeletionPreserveEnabledPersonalIdentity() {
        UUID owner=UUID.randomUUID();
        var user=new UserEntity(); user.setId(owner); user.setEmail("owner@example.test"); user.setPaymentEnabled(true); user.setPaymentUrlPath("/pay/unchanged");
        var users=mock(UserRepository.class); when(users.findById(owner)).thenReturn(Optional.of(user)); when(users.findByIdForUpdate(owner)).thenReturn(Optional.of(user));
        var pools=mock(PoolRepository.class); var members=mock(PoolMemberRepository.class);
        when(members.findByPoolId(any())).thenReturn(List.of()); when(members.findByPoolIdAndUserId(any(),any())).thenReturn(Optional.empty());
        var stored=new java.util.concurrent.atomic.AtomicReference<PoolEntity>();
        when(pools.findByOwnerId(owner)).thenAnswer(i->Optional.ofNullable(stored.get()));
        when(pools.findByIdForUpdate(any())).thenAnswer(i->Optional.ofNullable(stored.get()));
        when(pools.save(any())).thenAnswer(i->{var p=(PoolEntity)i.getArgument(0);stored.set(p);return p;});
        doAnswer(i->{stored.set(null);return null;}).when(pools).deleteById(any());
        var service=new PoolService(pools,members,users,mock(EmailSendService.class),"http://localhost",true);
        var controller=new UserController(users,mock(EmailVerificationService.class),null,service);
        assertTrue(((UserResponses.PublicProfile)controller.publicProfile(owner).body()).paymentEnabled());
        var created=service.create(owner,"Team");
        var paused=(UserResponses.PublicProfile)controller.publicProfile(owner).body();
        assertTrue(paused.paymentEnabled()); assertFalse(paused.personalLinkPaused()); assertTrue(paused.poolParticipationEnabled());
        assertEquals("/pay/unchanged",paused.paymentUrl()); assertTrue(user.isPaymentEnabled());
        service.delete(owner,created.id());
        var restored=(UserResponses.PublicProfile)controller.publicProfile(owner).body();
        assertTrue(restored.paymentEnabled()); assertFalse(restored.personalLinkPaused()); assertEquals(paused.paymentUrl(),restored.paymentUrl());
    }
    @Test void membershipInSomeoneElsesPoolDoesNotPauseThePersonalLink() {
        var pools=mock(PoolRepository.class); when(pools.findByOwnerId(any())).thenReturn(Optional.empty());
        var service=new PoolService(pools,mock(PoolMemberRepository.class),mock(UserRepository.class),mock(EmailSendService.class),"http://localhost",true);
        var user=new UserEntity(); user.setId(UUID.randomUUID()); user.setPaymentEnabled(true);
        assertTrue(UserResponses.PublicProfile.from(user,service.publicPoolName(user.getId())).paymentEnabled());
    }
}
