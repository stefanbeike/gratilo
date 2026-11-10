package com.gratilo.user.repository;
import com.gratilo.user.domain.PoolDissolutionNotice;
import io.micronaut.data.annotation.Repository;
import io.micronaut.data.repository.CrudRepository;
import java.util.*;
@Repository public interface PoolDissolutionNoticeRepository extends CrudRepository<PoolDissolutionNotice,UUID> {
    List<PoolDissolutionNotice> findByPoolId(UUID poolId);
}
