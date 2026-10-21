package com.gratilo.user.repository;
import com.gratilo.user.domain.PoolMemberEntity; import io.micronaut.data.annotation.Repository; import io.micronaut.data.repository.CrudRepository; import java.util.*;
@Repository public interface PoolMemberRepository extends CrudRepository<PoolMemberEntity,UUID> { @io.micronaut.data.annotation.Query("SELECT m.poolId FROM PoolMemberEntity m WHERE m.id = :id")
 Optional<UUID> findPoolIdByMemberId(UUID id);
 @io.micronaut.data.annotation.Query("SELECT m.poolId FROM PoolMemberEntity m WHERE m.invitationToken = :token")
 Optional<UUID> findPoolIdByInvitationToken(String token);
 List<PoolMemberEntity> findByPoolId(UUID poolId); List<PoolMemberEntity> findByUserId(UUID userId); Optional<PoolMemberEntity> findByInvitationToken(String token); Optional<PoolMemberEntity> findByPoolIdAndUserId(UUID poolId,UUID userId); }
