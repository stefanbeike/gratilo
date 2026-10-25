--liquibase formatted sql
--changeset gratilo:017-platform-fee-vat-pricing splitStatements:false
-- New VAT-inclusive snapshots are immutable after creation. No historical rows are changed.
ALTER TABLE payment_mgmt.payment_fee_audits
 ADD COLUMN platform_fee_net_minor BIGINT,
 ADD COLUMN platform_fee_tax_minor BIGINT,
 ADD COLUMN platform_fee_vat_percent NUMERIC(18,8);

CREATE OR REPLACE FUNCTION payment_mgmt.guard_pricing_history() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_TABLE_NAME = 'payment_fee_events' THEN RAISE EXCEPTION 'Financial events are append-only'; END IF;
 IF OLD.metadata->>'pricingVersion' IN ('3','4','5','6','7','8','9','10','11','12','13','14') AND
  (OLD.metadata IS DISTINCT FROM NEW.metadata OR OLD.amount_minor IS DISTINCT FROM NEW.amount_minor OR OLD.currency IS DISTINCT FROM NEW.currency) THEN
  RAISE EXCEPTION 'Frozen pricing must not be rewritten';
 END IF;
 RETURN NEW;
END;
$$;
