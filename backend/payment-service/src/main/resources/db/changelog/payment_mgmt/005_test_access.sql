--liquibase formatted sql
--changeset gratilo:005-test-access
ALTER TABLE payment_mgmt.connected_accounts
    ADD COLUMN test_access_enabled BOOLEAN NOT NULL DEFAULT FALSE;
--rollback ALTER TABLE payment_mgmt.connected_accounts DROP COLUMN test_access_enabled;
