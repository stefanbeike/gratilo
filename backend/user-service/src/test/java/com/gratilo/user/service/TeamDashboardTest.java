package com.gratilo.user.service;

import com.gratilo.user.domain.*;
import com.gratilo.user.repository.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class TeamDashboardTest {
    final UUID user=UUID.randomUUID(), other=UUID.randomUUID(), teamId=UUID.randomUUID();
    final PoolRepository pools=mock(PoolRepository.class);
    final PoolMemberRepository members=mock(PoolMemberRepository.class);
    final UserRepository users=mock(UserRepository.class);
    final PoolService service=new PoolService(pools,members,users,mock(EmailSendService.class),"http://localhost",true);
    TeamDashboardTest(){when(users.findByIdForUpdate(user)).thenReturn(Optional.of(new UserEntity()));}
    PoolMemberEntity membership(UUID team,boolean active,String status){var m=new PoolMemberEntity();m.setId(UUID.randomUUID());m.setPoolId(team);m.setUserId(user);m.setActive(active);m.setStatus(status);m.setSharePercent(50);return m;}
    PoolEntity pool(){var p=new PoolEntity();p.setId(teamId);p.setOwnerId(other);p.setName("Team");when(pools.findById(teamId)).thenReturn(Optional.of(p));return p;}
    @Test void createRejectsAnotherActiveTeamBeforeWriting(){
        when(members.findByUserId(user)).thenReturn(List.of(membership(UUID.randomUUID(),true,"ACTIVE")));
        assertThrows(IllegalStateException.class,()->service.create(user,"New"));verify(pools,never()).save(any());
        var order=inOrder(users,members);order.verify(users).findByIdForUpdate(user);order.verify(members).findByUserId(user);
    }
    @Test void acceptingInvitationRejectsExistingOwnedTeam(){
        var invitation=membership(teamId,true,"INVITED");when(members.findByInvitationToken("token")).thenReturn(Optional.of(invitation));
        var owned=new PoolEntity();owned.setId(UUID.randomUUID());when(pools.findByOwnerId(user)).thenReturn(Optional.of(owned));
        assertThrows(IllegalStateException.class,()->service.respond("token",user,true));assertEquals("INVITED",invitation.getStatus());verify(members,never()).update(any());
    }
    @Test void selfReactivationRejectsDifferentActiveTeam(){
        var m=membership(teamId,false,"ACTIVE");pool();when(members.findById(m.getId())).thenReturn(Optional.of(m));
        when(members.findByUserId(user)).thenReturn(List.of(m,membership(UUID.randomUUID(),true,"ACTIVE")));
        assertThrows(IllegalStateException.class,()->service.setMemberActive(user,m.getId(),true));assertFalse(m.isActive());verify(members,never()).update(any());
    }
    @Test void ownerReactivationUsesTheSameMemberLockAndGuard(){
        var p=pool();var ownerMember=membership(teamId,true,"ACTIVE");ownerMember.setUserId(other);
        when(members.findByPoolIdAndUserId(teamId,other)).thenReturn(Optional.of(ownerMember));
        var m=membership(teamId,false,"ACTIVE");when(members.findById(m.getId())).thenReturn(Optional.of(m));
        when(members.findByUserId(user)).thenReturn(List.of(membership(UUID.randomUUID(),true,"ACTIVE")));
        assertThrows(IllegalStateException.class,()->service.setActive(other,p.getId(),m.getId(),true));verify(users).findByIdForUpdate(user);assertFalse(m.isActive());
    }
    @Test void invitationsAndInactiveTeamsDoNotPreventCreatingTheFirstActiveTeam(){
        when(members.findByUserId(user)).thenReturn(List.of(membership(UUID.randomUUID(),false,"ACTIVE"),membership(UUID.randomUUID(),true,"INVITED")));
        when(pools.save(any())).thenAnswer(i->i.getArgument(0));
        assertNotNull(service.create(user,"First").id());verify(members).save(any());
    }
    @Test void normalMembershipResponseContainsOnlyOwnMembershipAndNoOtherInvitationSecrets(){
        var p=pool();var mine=membership(teamId,true,"ACTIVE");var foreign=membership(teamId,true,"INVITED");foreign.setUserId(other);foreign.setInvitationToken("foreign-secret");
        when(members.findByUserId(user)).thenReturn(List.of(mine));when(members.findByPoolId(teamId)).thenReturn(List.of(mine,foreign));
        var result=service.memberships(user).getFirst();assertEquals(1,result.members().size());assertEquals(user,result.members().getFirst().userId());
    }
    @Test void publicTeamDoesNotExposeNamesEmailsOrMemberIds(){
        var p=pool();p.setProfileImage("saved-image");when(pools.findByOwnerId(other)).thenReturn(Optional.of(p));
        var result=service.publicDetails(other);assertEquals("saved-image",result.profileImage());assertTrue(result.memberNames().isEmpty());verifyNoInteractions(members,users);
    }
    @Test void teamImageRequiresOwnershipAndRejectsInvalidImage(){
        pool();assertThrows(IllegalArgumentException.class,()->service.setImage(user,teamId,null));
        assertThrows(IllegalArgumentException.class,()->service.setImage(other,teamId,"data:image/svg+xml,<svg/>"));verify(pools,never()).update(any());
    }
    @Test void validTeamImageCanBeSavedAndRemovedIndependentlyOfPersonalImages() throws Exception{
        var p=pool();var image=new java.awt.image.BufferedImage(256,256,java.awt.image.BufferedImage.TYPE_INT_RGB);var bytes=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"JPEG",bytes);
        var value="data:image/jpeg;base64,"+Base64.getEncoder().encodeToString(bytes.toByteArray());when(pools.update(p)).thenReturn(p);
        assertEquals(value,service.setImage(other,teamId,value).profileImage());assertNull(service.setImage(other,teamId,null).profileImage());verifyNoInteractions(users);
    }
}
