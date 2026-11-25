--liquibase formatted sql

--changeset gratilo:020-payment-references
ALTER TABLE payment_mgmt.transactions ADD COLUMN public_payment_reference UUID;
UPDATE payment_mgmt.transactions SET public_payment_reference = gen_random_uuid()
    WHERE provider = 'STRIPE' AND type = 'TIP';
ALTER TABLE payment_mgmt.transactions ALTER COLUMN public_payment_reference SET DEFAULT gen_random_uuid();
CREATE UNIQUE INDEX transactions_public_payment_reference_idx
    ON payment_mgmt.transactions(public_payment_reference);
CREATE INDEX transactions_stripe_checkout_session_idx
    ON payment_mgmt.transactions(provider_reference) WHERE provider = 'STRIPE' AND type = 'TIP';

CREATE TABLE payment_mgmt.stripe_payment_attempt (
    payment_id UUID NOT NULL REFERENCES payment_mgmt.transactions(id),
    livemode BOOLEAN NOT NULL,
    stripe_account VARCHAR(255) NOT NULL,
    charge_id VARCHAR(255) NOT NULL,
    payment_intent_id VARCHAR(255) NOT NULL,
    method VARCHAR(80),
    wallet VARCHAR(80),
    charge_created_at TIMESTAMPTZ NOT NULL,
    paid_at TIMESTAMPTZ,
    charged_minor BIGINT,
    currency VARCHAR(3) NOT NULL,
    receipt_url TEXT,
    PRIMARY KEY (livemode, stripe_account, charge_id)
);
CREATE INDEX stripe_payment_attempt_payment_idx ON payment_mgmt.stripe_payment_attempt(payment_id, livemode);
CREATE INDEX stripe_payment_attempt_intent_idx ON payment_mgmt.stripe_payment_attempt(livemode, payment_intent_id);
CREATE INDEX stripe_payment_attempt_charge_idx ON payment_mgmt.stripe_payment_attempt(livemode, charge_id);

CREATE TABLE payment_mgmt.stripe_payment_reference (
    livemode BOOLEAN NOT NULL,
    stripe_account VARCHAR(255) NOT NULL,
    charge_id VARCHAR(255) NOT NULL,
    source VARCHAR(170) NOT NULL,
    value VARCHAR(255) NOT NULL,
    PRIMARY KEY(livemode, stripe_account, charge_id, source, value),
    FOREIGN KEY(livemode, stripe_account, charge_id)
        REFERENCES payment_mgmt.stripe_payment_attempt(livemode, stripe_account, charge_id)
);
CREATE INDEX stripe_payment_reference_lookup ON payment_mgmt.stripe_payment_reference(livemode, value);

--rollback DROP TABLE payment_mgmt.stripe_payment_reference;
--rollback DROP TABLE payment_mgmt.stripe_payment_attempt;
--rollback DROP INDEX payment_mgmt.transactions_stripe_checkout_session_idx;
--rollback ALTER TABLE payment_mgmt.transactions DROP COLUMN public_payment_reference;
