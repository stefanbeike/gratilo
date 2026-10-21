--liquibase formatted sql
--changeset gratilo:016-pool-payment-links splitStatements:false
-- No inventory is manufactured. Insufficient stock or conflicting legacy data aborts the entire migration.
ALTER TABLE user_mgmt.payment_url_pool
 ADD COLUMN public_path VARCHAR(120),
 ADD COLUMN assigned_pool_id UUID UNIQUE REFERENCES user_mgmt.pools(id) ON DELETE SET NULL,
 ADD COLUMN original_pool_id UUID UNIQUE,
 ADD COLUMN legacy_owner_id UUID UNIQUE,
 ADD COLUMN retired_at TIMESTAMPTZ;
UPDATE user_mgmt.payment_url_pool SET public_path=regexp_replace(payment_url,'^https?://[^/]+/pay/','');
ALTER TABLE user_mgmt.payment_url_pool ALTER COLUMN public_path SET NOT NULL;
ALTER TABLE user_mgmt.payment_url_pool ALTER COLUMN payment_url SET NOT NULL;
CREATE UNIQUE INDEX payment_url_public_path_unique ON user_mgmt.payment_url_pool(public_path);
CREATE UNIQUE INDEX payment_url_full_unique ON user_mgmt.payment_url_pool(payment_url);
-- Never recycle orphaned previously assigned personal URLs.
UPDATE user_mgmt.payment_url_pool SET retired_at=now() WHERE assigned_at IS NOT NULL AND assigned_user_id IS NULL;
UPDATE user_mgmt.payment_url_pool SET assigned_at=now() WHERE assigned_user_id IS NOT NULL AND assigned_at IS NULL;
ALTER TABLE user_mgmt.payment_url_pool ADD CONSTRAINT payment_url_target_check CHECK (
 NOT (assigned_user_id IS NOT NULL AND original_pool_id IS NOT NULL)
 AND (assigned_pool_id IS NULL OR (original_pool_id IS NOT NULL AND assigned_pool_id=original_pool_id))
 AND (legacy_owner_id IS NULL OR original_pool_id IS NOT NULL)
 AND (original_pool_id IS NULL OR assigned_pool_id IS NOT NULL OR retired_at IS NOT NULL)
 AND (retired_at IS NULL OR (assigned_user_id IS NULL AND assigned_pool_id IS NULL))
 AND (public_path ~ '^[A-Za-z0-9_-]{1,120}$')
 AND (assigned_at IS NOT NULL OR (assigned_user_id IS NULL AND original_pool_id IS NULL AND retired_at IS NULL))
);
-- Detect existing one-owner conflicts rather than silently choosing one pool.
CREATE UNIQUE INDEX pools_owner_unique ON user_mgmt.pools(owner_id);

CREATE FUNCTION user_mgmt.backfill_pool_payment_links() RETURNS INTEGER LANGUAGE plpgsql AS $$
DECLARE team RECORD; slot BIGINT; allocated INTEGER:=0;
BEGIN
 FOR team IN SELECT p.id,p.owner_id FROM user_mgmt.pools p
  WHERE NOT EXISTS (SELECT 1 FROM user_mgmt.payment_url_pool u WHERE u.assigned_pool_id=p.id)
  ORDER BY p.id FOR UPDATE OF p LOOP
  SELECT id INTO slot FROM user_mgmt.payment_url_pool WHERE assigned_at IS NULL AND assigned_user_id IS NULL
   AND assigned_pool_id IS NULL AND original_pool_id IS NULL AND retired_at IS NULL ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED;
  IF slot IS NULL THEN RAISE EXCEPTION 'Keine freien vorgefertigten Zahlungslinks verfügbar; Pool-Link-Migration abgebrochen'; END IF;
  UPDATE user_mgmt.payment_url_pool SET assigned_pool_id=team.id, original_pool_id=team.id, assigned_at=now(),
   legacy_owner_id=CASE WHEN EXISTS(SELECT 1 FROM user_mgmt.payment_url_pool WHERE legacy_owner_id=team.owner_id) THEN NULL ELSE team.owner_id END
   WHERE id=slot;
  allocated:=allocated+1;
 END LOOP;
 RETURN allocated;
END $$;
SELECT user_mgmt.backfill_pool_payment_links();

CREATE FUNCTION user_mgmt.protect_payment_link() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.public_path IS DISTINCT FROM NEW.public_path OR OLD.payment_url IS DISTINCT FROM NEW.payment_url THEN
  RAISE EXCEPTION 'Public payment links are immutable';
 END IF;
 IF OLD.assigned_at IS NOT NULL THEN
  IF OLD.assigned_at IS DISTINCT FROM NEW.assigned_at OR OLD.original_pool_id IS DISTINCT FROM NEW.original_pool_id
   OR OLD.legacy_owner_id IS DISTINCT FROM NEW.legacy_owner_id
   OR (NEW.assigned_user_id IS NOT NULL AND NEW.assigned_user_id IS DISTINCT FROM OLD.assigned_user_id)
   OR (NEW.assigned_pool_id IS NOT NULL AND NEW.assigned_pool_id IS DISTINCT FROM OLD.assigned_pool_id)
   OR (OLD.retired_at IS NOT NULL AND NEW.retired_at IS DISTINCT FROM OLD.retired_at) THEN
    RAISE EXCEPTION 'Used payment links cannot be reassigned';
  END IF;
  IF (OLD.assigned_user_id IS NOT NULL AND NEW.assigned_user_id IS NULL)
   OR (OLD.assigned_pool_id IS NOT NULL AND NEW.assigned_pool_id IS NULL) THEN NEW.retired_at:=COALESCE(NEW.retired_at,now()); END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER immutable_payment_link BEFORE UPDATE ON user_mgmt.payment_url_pool
 FOR EACH ROW EXECUTE FUNCTION user_mgmt.protect_payment_link();

CREATE FUNCTION user_mgmt.prevent_used_payment_link_deletion() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.assigned_at IS NOT NULL THEN RAISE EXCEPTION 'Used payment links must be retained'; END IF;
 RETURN OLD;
END $$;
CREATE TRIGGER retain_used_payment_link BEFORE DELETE ON user_mgmt.payment_url_pool
 FOR EACH ROW EXECUTE FUNCTION user_mgmt.prevent_used_payment_link_deletion();
