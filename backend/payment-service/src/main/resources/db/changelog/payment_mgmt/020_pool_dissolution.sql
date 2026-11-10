--liquibase formatted sql
--changeset gratilo:020-pool-dissolution
CREATE TABLE payment_mgmt.pool_checkout_gates (pool_id UUID PRIMARY KEY, closed BOOLEAN NOT NULL DEFAULT FALSE, closed_at TIMESTAMPTZ);
CREATE TABLE payment_mgmt.pool_checkout_reservations (id UUID PRIMARY KEY, pool_id UUID NOT NULL REFERENCES payment_mgmt.pool_checkout_gates(pool_id), created_at TIMESTAMPTZ NOT NULL DEFAULT now(), finished_at TIMESTAMPTZ);
CREATE INDEX pool_checkout_inflight_idx ON payment_mgmt.pool_checkout_reservations(pool_id) WHERE finished_at IS NULL;
CREATE INDEX pool_transaction_lookup_idx ON payment_mgmt.transactions((metadata->>'poolId')) WHERE metadata->>'poolId' IS NOT NULL;
