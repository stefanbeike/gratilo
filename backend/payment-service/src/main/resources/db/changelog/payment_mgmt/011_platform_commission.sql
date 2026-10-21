--liquibase formatted sql
--changeset gratilo:011-platform-commission
-- Nullable additions only. No historical backfill or repricing.
ALTER TABLE payment_mgmt.payment_fee_audits
 ADD COLUMN fee_source_reference VARCHAR(255), ADD COLUMN pricing_version INTEGER, ADD COLUMN platform_fee_minor BIGINT,
 ADD COLUMN platform_fee_percent NUMERIC(18,8), ADD COLUMN exact_estimate_percent NUMERIC(18,8),
 ADD COLUMN recipient_total_minor BIGINT, ADD COLUMN stripe_charge_id VARCHAR(255),
 ADD COLUMN stripe_payment_intent_id VARCHAR(255), ADD COLUMN stripe_application_fee_id VARCHAR(255),
 ADD COLUMN application_fee_minor BIGINT, ADD COLUMN refunded_minor BIGINT,
 ADD COLUMN application_fee_refunded_minor BIGINT, ADD COLUMN commission_refund_status VARCHAR(40),
 ADD COLUMN platform_net_revenue_minor BIGINT;
CREATE TABLE payment_mgmt.payment_fee_events (
 event_id VARCHAR(255) PRIMARY KEY, transaction_id UUID NOT NULL REFERENCES payment_mgmt.transactions(id),
 event_type VARCHAR(100) NOT NULL, financial_data TEXT NOT NULL, recorded_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX payment_fee_events_transaction_idx ON payment_mgmt.payment_fee_events(transaction_id);
--changeset gratilo:012-immutable-pricing splitStatements:false
CREATE FUNCTION payment_mgmt.guard_pricing_history() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_TABLE_NAME = 'payment_fee_events' THEN RAISE EXCEPTION 'Financial events are append-only'; END IF;
 IF OLD.metadata->>'pricingVersion' IN ('3','4','5') AND
  (OLD.metadata IS DISTINCT FROM NEW.metadata OR OLD.amount_minor IS DISTINCT FROM NEW.amount_minor OR OLD.currency IS DISTINCT FROM NEW.currency) THEN
  RAISE EXCEPTION 'Frozen pricing must not be rewritten';
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER immutable_pricing_v3 BEFORE UPDATE ON payment_mgmt.transactions FOR EACH ROW EXECUTE FUNCTION payment_mgmt.guard_pricing_history();
CREATE TRIGGER immutable_fee_events BEFORE UPDATE OR DELETE ON payment_mgmt.payment_fee_events FOR EACH ROW EXECUTE FUNCTION payment_mgmt.guard_pricing_history();
