--liquibase formatted sql
--changeset gratilo:014-tip-notification-outbox
CREATE TABLE payment_mgmt.tip_notification_outbox (
 notification_key varchar(180) PRIMARY KEY,
 transaction_id uuid NOT NULL REFERENCES payment_mgmt.transactions(id),
 recipient_user_id uuid,
 payload text NOT NULL,
 status varchar(24) NOT NULL DEFAULT 'PENDING',
 attempts integer NOT NULL DEFAULT 0,
 next_attempt_at timestamptz NOT NULL DEFAULT now(),
 lease_token uuid,
 lease_until timestamptz,
 last_error varchar(80),
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(transaction_id,recipient_user_id),
 CHECK(status IN ('PENDING','SENDING','RETRY','ACCEPTED','FAILED')),
 CHECK(recipient_user_id IS NOT NULL OR status='FAILED')
);
CREATE INDEX tip_notification_due_idx ON payment_mgmt.tip_notification_outbox(status,next_attempt_at);
