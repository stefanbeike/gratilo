--liquibase formatted sql

--changeset gratilo:008-pool-settlement-safety
-- Historical pending allocations require an explicit, separately reviewed release.
-- This migration never backfills transfers or changes frozen recipients/amounts.
ALTER TABLE payment_mgmt.pool_transfers
    ADD COLUMN settlement_approved BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN last_attempt_at TIMESTAMPTZ,
    ADD COLUMN first_attempt_at TIMESTAMPTZ,
    ADD COLUMN funds_available_at TIMESTAMPTZ,
    ADD COLUMN attempted_amount_minor BIGINT CHECK (attempted_amount_minor > 0),
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
--rollback ALTER TABLE payment_mgmt.pool_transfers DROP COLUMN settlement_approved, DROP COLUMN last_attempt_at, DROP COLUMN first_attempt_at, DROP COLUMN funds_available_at, DROP COLUMN attempted_amount_minor, DROP COLUMN version;
