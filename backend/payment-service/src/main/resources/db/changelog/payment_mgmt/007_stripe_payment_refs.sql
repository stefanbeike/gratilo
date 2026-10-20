--liquibase formatted sql

--changeset gratilo:007-stripe-payment-refs
ALTER TABLE payment_mgmt.transactions ADD COLUMN stripe_payment_intent_id VARCHAR(255);
ALTER TABLE payment_mgmt.transactions ADD COLUMN stripe_charge_id VARCHAR(255);
CREATE UNIQUE INDEX transactions_stripe_payment_intent_idx ON payment_mgmt.transactions(stripe_payment_intent_id) WHERE stripe_payment_intent_id IS NOT NULL;
CREATE UNIQUE INDEX transactions_stripe_charge_idx ON payment_mgmt.transactions(stripe_charge_id) WHERE stripe_charge_id IS NOT NULL;
--rollback DROP INDEX IF EXISTS payment_mgmt.transactions_stripe_charge_idx;
--rollback DROP INDEX IF EXISTS payment_mgmt.transactions_stripe_payment_intent_idx;
--rollback ALTER TABLE payment_mgmt.transactions DROP COLUMN IF EXISTS stripe_charge_id;
--rollback ALTER TABLE payment_mgmt.transactions DROP COLUMN IF EXISTS stripe_payment_intent_id;
