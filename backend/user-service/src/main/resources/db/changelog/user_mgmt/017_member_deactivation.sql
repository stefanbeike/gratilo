--liquibase formatted sql
--changeset gratilo:017-member-deactivation
ALTER TABLE user_mgmt.pool_members ADD COLUMN deactivation_reason VARCHAR(16);
-- The old active flag did not record who disabled the member. Fail closed for
-- these ambiguous rows, without claiming that the owner actually disabled them.
UPDATE user_mgmt.pool_members SET deactivation_reason='LEGACY_UNKNOWN' WHERE active=FALSE;
ALTER TABLE user_mgmt.pool_members ADD CONSTRAINT pool_member_deactivation_reason_check CHECK (
 deactivation_reason IS NULL OR (deactivation_reason IN ('OWNER','SELF','LEGACY_UNKNOWN') AND active=FALSE)
);
-- Existing UNIQUE(pool_id,user_id) also protects retained LEFT memberships.
