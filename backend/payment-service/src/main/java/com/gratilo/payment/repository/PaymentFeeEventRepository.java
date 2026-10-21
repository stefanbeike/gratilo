package com.gratilo.payment.repository;
import com.gratilo.payment.domain.PaymentFeeEventEntity;
import io.micronaut.data.annotation.Repository;
import io.micronaut.data.repository.CrudRepository;
import java.util.*;
@Repository public interface PaymentFeeEventRepository extends CrudRepository<PaymentFeeEventEntity,String>{List<PaymentFeeEventEntity> findByTransactionId(UUID transactionId);}
