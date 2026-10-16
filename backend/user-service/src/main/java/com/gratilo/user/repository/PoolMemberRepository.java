package com.gratilo.user.repository;
import com.gratilo.user.domain.PoolMemberEntity; import io.micronaut.data.annotation.Repository; import io.micronaut.data.repository.CrudRepository; import java.util.*;
@Repository public interface PoolMemberRepository extends CrudRepository<PoolMemberEntity,UUID> { List<PoolMemberEntity> findByPoolId(UUID poolId); List<PoolMemberEntity> findByUserId(UUID userId); Optional<PoolMemberEntity> findByInvitationToken(String token); Optional<PoolMemberEntity> findByPoolIdAndUserId(UUID poolId,UUID userId); }
