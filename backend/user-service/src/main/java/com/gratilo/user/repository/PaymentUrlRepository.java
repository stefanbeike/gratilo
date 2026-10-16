package com.gratilo.user.repository;
import com.gratilo.user.domain.PaymentUrlEntity; import io.micronaut.data.annotation.Repository; import io.micronaut.data.repository.CrudRepository; import java.util.*;
@Repository public interface PaymentUrlRepository extends CrudRepository<PaymentUrlEntity,Long> { Optional<PaymentUrlEntity> findFirstByAssignedUserIdIsNullOrderById(); Optional<PaymentUrlEntity> findByAssignedUserId(UUID id); Optional<PaymentUrlEntity> findByPaymentUrl(String paymentUrl); long countByAssignedUserIdIsNotNull(); }
