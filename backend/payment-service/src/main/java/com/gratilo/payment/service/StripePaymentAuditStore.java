package com.gratilo.payment.service;

import com.gratilo.payment.domain.StripePaymentAuditEntity;
import java.util.*;
import java.util.function.Consumer;

public interface StripePaymentAuditStore {
    void enroll(StripePaymentAuditEntity row);
    Optional<StripePaymentAuditEntity> find(UUID paymentId);
    List<StripePaymentAuditEntity> search(String reference,boolean livemode);
    List<UUID> due(boolean livemode,int limit);
    void locked(UUID paymentId,Consumer<StripePaymentAuditEntity> work);
    void snapshot(StripePaymentAuditEntity row,List<Map<String,Object>> transfers,List<Map<String,Object>> fees);
    boolean event(UUID paymentId,String key,String type,String financialData);
    List<Map<String,Object>> events(UUID paymentId,long after,int limit);
}
