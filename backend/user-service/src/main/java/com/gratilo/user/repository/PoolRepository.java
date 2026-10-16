package com.gratilo.user.repository;
import com.gratilo.user.domain.PoolEntity; import io.micronaut.data.annotation.Repository; import io.micronaut.data.repository.CrudRepository; import java.util.*;
@Repository public interface PoolRepository extends CrudRepository<PoolEntity,UUID> { Optional<PoolEntity> findByOwnerId(UUID ownerId); }
