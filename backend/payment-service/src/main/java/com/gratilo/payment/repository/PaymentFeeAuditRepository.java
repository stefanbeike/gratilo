package com.gratilo.payment.repository;
import com.gratilo.payment.domain.PaymentFeeAuditEntity;
import io.micronaut.data.annotation.Repository;
import io.micronaut.data.repository.CrudRepository;
import java.util.*;
@Repository public interface PaymentFeeAuditRepository extends CrudRepository<PaymentFeeAuditEntity,UUID> {
    List<PaymentFeeAuditEntity> findByStatus(String status);
}
