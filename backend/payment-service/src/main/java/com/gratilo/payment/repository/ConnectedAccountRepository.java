package com.gratilo.payment.repository;
import com.gratilo.payment.domain.ConnectedAccountEntity;
import io.micronaut.data.annotation.Repository;
import io.micronaut.data.repository.CrudRepository;
import java.util.*;
@Repository public interface ConnectedAccountRepository extends CrudRepository<ConnectedAccountEntity, UUID> { Optional<ConnectedAccountEntity> findByUserId(UUID userId); Optional<ConnectedAccountEntity> findByStripeAccountId(String stripeAccountId); }
