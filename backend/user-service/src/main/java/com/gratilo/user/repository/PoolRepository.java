package com.gratilo.user.repository;
import com.gratilo.user.domain.PoolEntity; import io.micronaut.data.annotation.Repository; import io.micronaut.data.repository.CrudRepository; import java.util.*;
@Repository public interface PoolRepository extends CrudRepository<PoolEntity,UUID> {
 Optional<PoolEntity> findByOwnerId(UUID ownerId);
 List<PoolEntity> findAllByOwnerIdOrderByCreatedAtAsc(UUID ownerId);
 Optional<PoolEntity> findFirstByOwnerIdOrderByCreatedAtAsc(UUID ownerId);
 Optional<PoolEntity> findByPublicPath(String publicPath);
 @io.micronaut.data.annotation.Query(value="SELECT * FROM user_mgmt.pools WHERE id = :id FOR UPDATE", nativeQuery=true)
 Optional<PoolEntity> findByIdForUpdate(UUID id);
 @io.micronaut.data.annotation.Query(value="SELECT p.* FROM user_mgmt.pools p JOIN user_mgmt.payment_url_pool u ON u.assigned_pool_id=p.id WHERE u.legacy_owner_id=:ownerId AND u.retired_at IS NULL", nativeQuery=true)
 Optional<PoolEntity> findByLegacyOwnerId(UUID ownerId); }
