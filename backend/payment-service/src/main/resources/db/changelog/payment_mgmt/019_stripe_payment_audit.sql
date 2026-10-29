--liquibase formatted sql
--changeset gratilo:019-stripe-payment-audit splitStatements:false
-- New enrollment only. Deliberately no INSERT SELECT, historical UPDATE or backfill.
CREATE TABLE payment_mgmt.stripe_payment_audit (
    payment_id UUID PRIMARY KEY REFERENCES payment_mgmt.transactions(id),
    UNIQUE(payment_id,livemode),
    payment_type VARCHAR(8) NOT NULL CHECK (payment_type IN ('SINGLE','POOL')),
    livemode BOOLEAN NOT NULL,
    platform_account_id VARCHAR(255),
    charge_account_id VARCHAR(255),
    checkout_session_id VARCHAR(255),
    payment_intent_id VARCHAR(255),
    charge_id VARCHAR(255),
    balance_transaction_id VARCHAR(255),
    currency VARCHAR(3) NOT NULL,
    expected_gross_minor BIGINT NOT NULL CHECK (expected_gross_minor > 0),
    recipient_target_minor BIGINT NOT NULL CHECK (recipient_target_minor >= 0),
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    financial_data TEXT NOT NULL DEFAULT '{}',
    external_costs TEXT NOT NULL DEFAULT '[]',
    snapshot_hash VARCHAR(64),
    revision BIGINT NOT NULL DEFAULT 0,
    last_error VARCHAR(80),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    checked_at TIMESTAMPTZ,
    next_check_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX stripe_payment_audit_due ON payment_mgmt.stripe_payment_audit(livemode,next_check_at);
CREATE UNIQUE INDEX stripe_payment_audit_session ON payment_mgmt.stripe_payment_audit(livemode,COALESCE(charge_account_id,''),checkout_session_id) WHERE checkout_session_id IS NOT NULL;
CREATE INDEX stripe_payment_audit_pi ON payment_mgmt.stripe_payment_audit(livemode,payment_intent_id);
CREATE INDEX stripe_payment_audit_charge ON payment_mgmt.stripe_payment_audit(livemode,charge_id);
CREATE TABLE payment_mgmt.stripe_payment_transfer_audit (
    payment_id UUID NOT NULL REFERENCES payment_mgmt.stripe_payment_audit(payment_id),
    transfer_id VARCHAR(255) NOT NULL,
    livemode BOOLEAN NOT NULL,
    destination_account_id VARCHAR(255) NOT NULL,
    source_charge_id VARCHAR(255) NOT NULL,
    amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
    reversed_minor BIGINT NOT NULL CHECK (reversed_minor >= 0 AND reversed_minor <= amount_minor),
    currency VARCHAR(3) NOT NULL,
    balance_transaction_id VARCHAR(255),
    destination_payment_id VARCHAR(255),
    recipient_balance_transaction_id VARCHAR(255),
    financial_data TEXT NOT NULL,
    observed_revision BIGINT NOT NULL DEFAULT 0,
    FOREIGN KEY(payment_id,livemode) REFERENCES payment_mgmt.stripe_payment_audit(payment_id,livemode),
    PRIMARY KEY(payment_id,transfer_id)
);
CREATE INDEX stripe_payment_transfer_audit_lookup ON payment_mgmt.stripe_payment_transfer_audit(livemode,transfer_id);
CREATE TABLE payment_mgmt.stripe_payment_fee_audit (
    payment_id UUID NOT NULL REFERENCES payment_mgmt.stripe_payment_audit(payment_id),
    cost_key VARCHAR(600) NOT NULL,
    livemode BOOLEAN NOT NULL,
    account_id VARCHAR(255) NOT NULL,
    source_id VARCHAR(255) NOT NULL,
    fee_type VARCHAR(80) NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    financial_data TEXT NOT NULL,
    observed_revision BIGINT NOT NULL DEFAULT 0,
    FOREIGN KEY(payment_id,livemode) REFERENCES payment_mgmt.stripe_payment_audit(payment_id,livemode),
    PRIMARY KEY(payment_id,cost_key)
);
CREATE TABLE payment_mgmt.stripe_payment_audit_event (
    id BIGSERIAL PRIMARY KEY,
    payment_id UUID NOT NULL REFERENCES payment_mgmt.stripe_payment_audit(payment_id),
    event_key VARCHAR(255) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    financial_data TEXT NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(payment_id,event_key)
);
CREATE INDEX stripe_payment_audit_event_history ON payment_mgmt.stripe_payment_audit_event(payment_id,id);
CREATE FUNCTION payment_mgmt.reject_stripe_audit_event_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'stripe_payment_audit_event is append-only';
END;
$$;
CREATE TRIGGER stripe_payment_audit_event_immutable BEFORE UPDATE OR DELETE ON payment_mgmt.stripe_payment_audit_event
FOR EACH ROW EXECUTE FUNCTION payment_mgmt.reject_stripe_audit_event_mutation();

CREATE TRIGGER stripe_payment_audit_event_no_truncate BEFORE TRUNCATE ON payment_mgmt.stripe_payment_audit_event
FOR EACH STATEMENT EXECUTE FUNCTION payment_mgmt.reject_stripe_audit_event_mutation();
