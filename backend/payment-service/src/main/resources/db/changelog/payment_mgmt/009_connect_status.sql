--liquibase formatted sql
--changeset gratilo:009-connect-status
ALTER TABLE payment_mgmt.connected_accounts ADD COLUMN transfers_enabled boolean NOT NULL DEFAULT false;
ALTER TABLE payment_mgmt.connected_accounts ADD COLUMN stripe_status varchar(32) NOT NULL DEFAULT 'UNAVAILABLE';
ALTER TABLE payment_mgmt.connected_accounts ADD COLUMN status_checked_at timestamptz;
--rollback ALTER TABLE payment_mgmt.connected_accounts DROP COLUMN status_checked_at, DROP COLUMN stripe_status, DROP COLUMN transfers_enabled;
