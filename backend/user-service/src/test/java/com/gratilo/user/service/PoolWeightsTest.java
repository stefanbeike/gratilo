package com.gratilo.user.service;
import com.gratilo.user.domain.*;
import com.gratilo.user.repository.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class PoolWeightsTest {
 static PoolMemberEntity member(int index,double weight,boolean active){var m=new PoolMemberEntity();m.setId(new UUID(0,index));m.setUserId(new UUID(1,index));m.setSharePercent(weight);m.setStatus("ACTIVE");m.setActive(active);return m;}
 @Test void deterministicRoundingAlwaysTotalsOneHundredPercent(){
  var members=List.of(member(1,1,true),member(2,1,true),member(3,1,true));var split=PoolWeights.effectiveShares(members);
  assertEquals(33.34,split.get(members.getFirst().getId()));assertEquals(10000,split.values().stream().mapToInt(v->(int)Math.round(v*100)).sum());
  assertEquals(split,PoolWeights.effectiveShares(List.of(members.get(2),members.get(0),members.get(1))));
 }
 @Test void inactiveAndInvitedMembersHaveZeroShareAndReactivationRestoresSavedWeights(){
  var a=member(1,30,true);var b=member(2,70,false);var invited=member(3,20,true);invited.setStatus("INVITED");
  assertEquals(Map.of(a.getId(),100d,b.getId(),0d,invited.getId(),0d),PoolWeights.effectiveShares(List.of(a,b,invited)));
  b.setActive(true);var split=PoolWeights.effectiveShares(List.of(a,b,invited));assertEquals(30,split.get(a.getId()));assertEquals(70,split.get(b.getId()));assertEquals(70,b.getSharePercent());
  assertEquals(100,PoolWeights.effectiveShares(List.of(a)).get(a.getId()));
 }
 @Test void noPositiveActiveWeightMeansZeroDistribution(){assertEquals(0,PoolWeights.effectiveShares(List.of(member(1,0,true))).values().stream().mapToDouble(Double::doubleValue).sum());}
 @Test void membershipActionsPreserveWeightsAndExposeNewEffectiveShares(){
  var a=member(1,30,true);var b=member(2,70,true);UUID poolId=UUID.randomUUID();a.setPoolId(poolId);b.setPoolId(poolId);
  var p=new PoolEntity();p.setId(poolId);p.setOwnerId(a.getUserId());p.setName("Team");
  var pools=mock(PoolRepository.class);when(pools.findByIdForUpdate(poolId)).thenReturn(Optional.of(p));
  var members=mock(PoolMemberRepository.class);var stored=new ArrayList<>(List.of(a,b));when(members.findByPoolId(poolId)).thenAnswer(i->List.copyOf(stored));
  when(members.findById(b.getId())).thenReturn(Optional.of(b));when(members.findByPoolIdAndUserId(poolId,a.getUserId())).thenReturn(Optional.of(a));
  doAnswer(i->{stored.remove(b);return null;}).when(members).deleteById(b.getId());
  var users=mock(UserRepository.class);when(users.findById(any())).thenReturn(Optional.empty());when(users.findByIdForUpdate(any())).thenReturn(Optional.of(new UserEntity()));
  var service=new PoolService(pools,members,users,mock(EmailSendService.class),"http://localhost",true);
  var response=service.setActive(a.getUserId(),poolId,b.getId(),false);assertEquals(100,response.members().getFirst().effectiveSharePercent());assertEquals(0,response.members().get(1).effectiveSharePercent());assertEquals(70,b.getSharePercent());
  response=service.setActive(a.getUserId(),poolId,b.getId(),true);assertEquals(30,response.members().getFirst().effectiveSharePercent());assertEquals(70,response.members().get(1).effectiveSharePercent());
  response=service.remove(a.getUserId(),poolId,b.getId());assertEquals(1,response.members().size());assertEquals(100,response.members().getFirst().effectiveSharePercent());
 }
}
