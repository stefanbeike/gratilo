--liquibase formatted sql
--changeset gratilo:018-tip-notification-inbox
CREATE TABLE user_mgmt.tip_notification_inbox (
 notification_key varchar(180) PRIMARY KEY,
 recipient_user_id uuid NOT NULL,
 payload text NOT NULL,
 status varchar(24) NOT NULL DEFAULT 'PENDING',
 attempts integer NOT NULL DEFAULT 0,
 next_attempt_at timestamptz NOT NULL DEFAULT now(),
 lease_token uuid,
 lease_until timestamptz,
 last_error varchar(80),
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now(),
 CHECK(status IN ('PENDING','SENDING','RETRY','SENT','SUPPRESSED','UNKNOWN','FAILED'))
);
CREATE INDEX tip_notification_inbox_due_idx ON user_mgmt.tip_notification_inbox(status,next_attempt_at);
