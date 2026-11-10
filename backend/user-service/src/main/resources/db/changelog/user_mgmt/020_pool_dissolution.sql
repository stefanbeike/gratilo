--liquibase formatted sql
--changeset gratilo:020-pool-dissolution
ALTER TABLE user_mgmt.pools ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE', ADD COLUMN dissolution_started_at TIMESTAMPTZ, ADD COLUMN deleted_at TIMESTAMPTZ;
ALTER TABLE user_mgmt.pools ADD CONSTRAINT pool_lifecycle CHECK ((status IN ('ACTIVE','CLOSING') AND deleted_at IS NULL) OR (status='DELETED' AND deleted_at IS NOT NULL));
ALTER TABLE user_mgmt.pool_members DROP CONSTRAINT pool_members_pool_id_fkey;
ALTER TABLE user_mgmt.pool_members ADD CONSTRAINT pool_members_pool_id_fkey FOREIGN KEY(pool_id) REFERENCES user_mgmt.pools(id) ON DELETE RESTRICT;
ALTER TABLE user_mgmt.pool_members DROP CONSTRAINT pool_member_deactivation_reason_check;
ALTER TABLE user_mgmt.pool_members ADD CONSTRAINT pool_member_deactivation_reason_check CHECK (deactivation_reason IS NULL OR (deactivation_reason IN ('OWNER','SELF','LEGACY_UNKNOWN','DISSOLVED') AND active=FALSE));
ALTER TABLE user_mgmt.pool_members ADD COLUMN ended_at TIMESTAMPTZ;
CREATE TABLE user_mgmt.pool_dissolution_outbox (
 id UUID PRIMARY KEY, pool_id UUID NOT NULL REFERENCES user_mgmt.pools(id) ON DELETE RESTRICT,
 user_id UUID NOT NULL REFERENCES user_mgmt.users(id) ON DELETE RESTRICT,
 pool_name VARCHAR(120) NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'PENDING', attempts INTEGER NOT NULL DEFAULT 0,
 next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(), lease_token UUID, lease_until TIMESTAMPTZ,
 last_error VARCHAR(80), created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(pool_id,user_id)
);
CREATE INDEX pool_dissolution_dispatch_idx ON user_mgmt.pool_dissolution_outbox(next_attempt_at) WHERE status IN ('PENDING','RETRY');
