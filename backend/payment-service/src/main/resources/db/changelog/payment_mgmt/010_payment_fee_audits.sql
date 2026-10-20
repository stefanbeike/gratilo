--liquibase formatted sql
--changeset gratilo:010-payment-fee-audits
-- No backfill: previously confirmed payments and allocation snapshots remain untouched.
CREATE TABLE payment_mgmt.payment_fee_audits (
 transaction_id UUID PRIMARY KEY REFERENCES payment_mgmt.transactions(id),
 charged_minor BIGINT NOT NULL CHECK(charged_minor>0),
 surcharge_minor BIGINT NOT NULL CHECK(surcharge_minor>=0),
 estimated_fee_minor BIGINT NOT NULL CHECK(estimated_fee_minor>=0),
 estimate_percent DOUBLE PRECISION NOT NULL,
 estimate_fixed_minor BIGINT NOT NULL,
 estimate_basis VARCHAR(255) NOT NULL,
 status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
 actual_total_fee_minor BIGINT, actual_fee_minor BIGINT, actual_net_minor BIGINT, difference_minor BIGINT, uncovered_minor BIGINT,
 balance_transaction_id VARCHAR(255), fee_details TEXT, last_error VARCHAR(80), checked_at TIMESTAMPTZ
);
CREATE INDEX payment_fee_audits_pending_idx ON payment_mgmt.payment_fee_audits(status);
--rollback DROP TABLE payment_mgmt.payment_fee_audits;
