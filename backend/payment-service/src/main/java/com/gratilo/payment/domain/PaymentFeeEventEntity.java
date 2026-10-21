package com.gratilo.payment.domain;
import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;
import java.util.UUID;
@Entity @Table(name="payment_fee_events",schema="payment_mgmt") @Getter @Setter @NoArgsConstructor
public class PaymentFeeEventEntity {
 @Id @Column(name="event_id") private String eventId;
 @Column(name="transaction_id",nullable=false) private UUID transactionId;
 @Column(name="event_type",nullable=false) private String eventType;
 @Column(name="financial_data",nullable=false,columnDefinition="text") private String financialData;
 @Column(name="recorded_at",nullable=false) private OffsetDateTime recordedAt;
}
