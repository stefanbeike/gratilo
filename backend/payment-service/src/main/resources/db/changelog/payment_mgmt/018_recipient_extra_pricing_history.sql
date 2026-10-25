--liquibase formatted sql
--changeset gratilo:018-recipient-extra-pricing-history splitStatements:false
-- Keep V15/V16 snapshots immutable in exactly the same way as prior pricing versions.
CREATE OR REPLACE FUNCTION payment_mgmt.guard_pricing_history() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_TABLE_NAME = 'payment_fee_events' THEN RAISE EXCEPTION 'Financial events are append-only'; END IF;
 IF OLD.metadata->>'pricingVersion' IN ('3','4','5','6','7','8','9','10','11','12','13','14','15','16') AND
  (OLD.metadata IS DISTINCT FROM NEW.metadata OR OLD.amount_minor IS DISTINCT FROM NEW.amount_minor OR OLD.currency IS DISTINCT FROM NEW.currency) THEN
  RAISE EXCEPTION 'Frozen pricing must not be rewritten';
 END IF;
 RETURN NEW;
END;
$$;
