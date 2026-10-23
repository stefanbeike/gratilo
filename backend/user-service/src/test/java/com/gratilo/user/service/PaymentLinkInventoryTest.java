package com.gratilo.user.service;

import com.gratilo.user.domain.*;
import com.gratilo.user.repository.*;
import com.gratilo.user.api.UserResponses;
import com.gratilo.user.controller.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class PaymentLinkInventoryTest {
    final PaymentUrlRepository urls=mock(PaymentUrlRepository.class);
    final UserRepository users=mock(UserRepository.class);
    final PoolRepository pools=mock(PoolRepository.class);
    final PoolMemberRepository members=mock(PoolMemberRepository.class);
    final PaymentUrlPoolService links=new PaymentUrlPoolService(urls,users,pools,mock(EmailSendService.class),"http://localhost:3000");
    PaymentUrlEntity slot(long id) {var s=new PaymentUrlEntity();s.setId(id);s.setPublicPath("gratilo-"+id);s.setPaymentUrl("https://gratilo.eu/pay/gratilo-"+id);return s;}
    PoolEntity pool() {var p=new PoolEntity();p.setId(UUID.randomUUID());p.setOwnerId(UUID.randomUUID());p.setName("Team");when(pools.findByIdForUpdate(p.getId())).thenReturn(Optional.of(p));return p;}

    @Test void reservesExistingInventoryUrlWithoutRewritingOrCreatingIt() {
        var p=pool();var slot=slot(2);when(urls.countByAssignedAtIsNullAndAssignedUserIdIsNullAndAssignedPoolIdIsNullAndOriginalPoolIdIsNullAndRetiredAtIsNull()).thenReturn(1L);when(urls.findAvailableForUpdate()).thenReturn(Optional.of(slot));
        assertEquals(slot.getPaymentUrl(),links.assignPool(p.getId()));
        assertEquals(p.getId(),slot.getAssignedPoolId());assertEquals(p.getId(),slot.getOriginalPoolId());
        assertEquals(p.getOwnerId(),slot.getLegacyOwnerId());assertNull(slot.getAssignedUserId());assertNotNull(slot.getAssignedAt());
        verify(urls).update(slot);verify(urls,never()).save(any());
    }
    @Test void repeatedAssignmentReturnsSameSlotAndDoesNotConsumeAnother() {
        var p=pool();var slot=slot(2);slot.setAssignedPoolId(p.getId());when(urls.findByAssignedPoolId(p.getId())).thenReturn(Optional.of(slot));
        assertEquals(slot.getPaymentUrl(),links.assignPool(p.getId()));verify(urls,never()).findAvailableForUpdate();verify(urls,never()).update(any());
    }
    @Test void exhaustedInventoryFailsWithUnderstandableError() {
        var p=pool();assertTrue(assertThrows(IllegalStateException.class,()->links.assignPool(p.getId())).getMessage().contains("Keine freien"));verify(urls,never()).update(any());
    }
    @Test void legacyOwnerAliasCannotMoveFromDeletedTeamToReplacement() {
        var p=pool();var retired=slot(1);retired.setRetiredAt(java.time.OffsetDateTime.now());
        when(urls.findByLegacyOwnerId(p.getOwnerId())).thenReturn(Optional.of(retired));var next=slot(2);when(urls.findAvailableForUpdate()).thenReturn(Optional.of(next));
        when(urls.countByAssignedAtIsNullAndAssignedUserIdIsNullAndAssignedPoolIdIsNullAndOriginalPoolIdIsNullAndRetiredAtIsNull()).thenReturn(1L);links.assignPool(p.getId());assertNull(next.getLegacyOwnerId());
    }
    @Test void personalAndTeamPathsResolveToDifferentTargetsForSameOwner() {
        var p=pool();var user=new UserEntity();user.setId(p.getOwnerId());user.setFirstName("Alex");user.setPaymentEnabled(true);
        var personal=slot(1);personal.setAssignedUserId(user.getId());var team=slot(2);team.setAssignedPoolId(p.getId());
        when(urls.findByPublicPath(personal.getPublicPath())).thenReturn(Optional.of(personal));when(urls.findByPublicPath(team.getPublicPath())).thenReturn(Optional.of(team));
        when(users.findById(user.getId())).thenReturn(Optional.of(user));when(pools.findById(p.getId())).thenReturn(Optional.of(p));
        var service=new PoolService(pools,members,users,mock(EmailSendService.class),"http://localhost",true);service.paymentLinks=links;
        var controller=new UserController(users,mock(EmailVerificationService.class),links,service);
        var profile=(UserResponses.PublicProfile)controller.publicPaymentProfile(personal.getPublicPath()).body();
        assertTrue(profile.paymentEnabled());assertFalse(profile.personalLinkPaused());assertNull(profile.poolName());assertEquals("Alex",profile.firstName());
        var publicTeam=(com.gratilo.user.api.PoolResponses.PublicPool)controller.publicPaymentProfile(team.getPublicPath()).body();
        assertEquals(p.getId(),publicTeam.poolId());assertEquals("POOL",publicTeam.paymentTarget());assertTrue(publicTeam.memberNames().isEmpty());
        assertNull(links.userForPath(team.getPublicPath()));assertTrue(links.poolForPath(personal.getPublicPath()).isEmpty());
    }
    @Test void retiredQrAndMalformedPathCannotResolve() {
        var retired=slot(1);retired.setAssignedPoolId(UUID.randomUUID());retired.setRetiredAt(java.time.OffsetDateTime.now());
        when(urls.findByPublicPath(retired.getPublicPath())).thenReturn(Optional.of(retired));
        assertTrue(links.poolForPath(retired.getPublicPath()).isEmpty());assertNull(links.userForPath(retired.getPublicPath()));assertTrue(links.slot("../other?owner=x").isEmpty());
    }
    @Test void personalManualDeactivationRemainsRespectedForPoolOwner() {
        var u=new UserEntity();u.setId(UUID.randomUUID());u.setPaymentEnabled(false);
        assertFalse(UserResponses.PublicProfile.from(u,"Team").paymentEnabled());assertFalse(UserResponses.PublicProfile.from(u,"Team").personalLinkPaused());
    }
    @Test void anotherUserCannotDeleteOrRenamePoolOrConsumeLink() {
        var p=pool();when(pools.findById(p.getId())).thenReturn(Optional.of(p));
        var service=new PoolService(pools,members,users,mock(EmailSendService.class),"http://localhost",true);service.paymentLinks=links;
        assertThrows(IllegalArgumentException.class,()->service.delete(UUID.randomUUID(),p.getId()));assertThrows(IllegalArgumentException.class,()->service.rename(UUID.randomUUID(),p.getId(),"Other"));
        verifyNoInteractions(urls);
    }
    @Test void repeatedCreateCreatesIndependentPoolsWithoutNeedingInventory() {
        var owner=UUID.randomUUID();var u=new UserEntity();u.setId(owner);when(users.findByIdForUpdate(owner)).thenReturn(Optional.of(u));
        when(pools.save(any())).thenAnswer(invocation->invocation.getArgument(0));
        when(members.findByPoolIdAndUserId(any(),eq(owner))).thenReturn(Optional.empty());
        var service=new PoolService(pools,members,users,mock(EmailSendService.class),"http://localhost",true);
        var first=service.create(owner,"First");var second=service.create(owner,"Second");
        assertNotEquals(first.id(),second.id());assertNotEquals(first.paymentUrl(),second.paymentUrl());
        verify(pools,times(2)).save(any());verify(members,times(2)).save(any());
    }
}
