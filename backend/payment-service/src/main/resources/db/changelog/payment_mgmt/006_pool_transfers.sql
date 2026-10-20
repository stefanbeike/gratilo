--liquibase formatted sql

--changeset gratilo:006-pool-transfers
CREATE TABLE payment_mgmt.pool_transfers (
    id UUID PRIMARY KEY,
    transaction_id UUID NOT NULL REFERENCES payment_mgmt.transactions(id),
    recipient_user_id UUID NOT NULL,
    stripe_account_id VARCHAR(255) NOT NULL,
    share_percent NUMERIC(7,4) NOT NULL CHECK (share_percent > 0 AND share_percent <= 100),
    amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    stripe_transfer_id VARCHAR(255) UNIQUE,
    stripe_reversal_id VARCHAR(255),
    reversal_amount_minor BIGINT NOT NULL DEFAULT 0 CHECK (reversal_amount_minor >= 0),
    reversed_amount_minor BIGINT NOT NULL DEFAULT 0 CHECK (reversed_amount_minor >= 0),
    failure_code VARCHAR(80),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pool_transfer_transaction_member_uq UNIQUE (transaction_id, recipient_user_id)
);
CREATE INDEX pool_transfers_status_created_idx ON payment_mgmt.pool_transfers(status, created_at);
CREATE INDEX pool_transfers_transaction_idx ON payment_mgmt.pool_transfers(transaction_id);
--rollback DROP TABLE IF EXISTS payment_mgmt.pool_transfers;
