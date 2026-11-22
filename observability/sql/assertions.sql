\set ON_ERROR_STOP on
BEGIN;
SET LOCAL ROLE gratilo_grafana;
DO $$
DECLARE
  all_start timestamptz := now()-interval '90 days';
  tip numeric;
  pieces numeric;
BEGIN
  IF has_table_privilege(current_user,(SELECT c.oid FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='payment_mgmt' AND c.relname='transactions'),'SELECT,INSERT,UPDATE,DELETE')
    OR has_table_privilege(current_user,(SELECT c.oid FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='user_mgmt' AND c.relname='users'),'SELECT,INSERT,UPDATE,DELETE')
    OR has_table_privilege(current_user,'gratilo_monitor.payment_rows','SELECT')
    OR has_function_privilege(current_user,'gratilo_monitor.operations()','EXECUTE')
    OR has_schema_privilege(current_user,'gratilo_monitor','CREATE') THEN
    RAISE EXCEPTION 'Unexpected Grafana access';
  END IF;
  SELECT value INTO tip FROM gratilo_monitor.payments(all_start,now(),false,'ALL') WHERE metric='tip_volume';
  IF tip IS DISTINCT FROM 194500 OR (SELECT value FROM gratilo_monitor.payments(all_start,now(),false,'ALL') WHERE metric='booked_count') IS DISTINCT FROM 170 THEN
    RAISE EXCEPTION 'Incorrect booked EUR test volume or count: %',tip;
  END IF;
  SELECT sum(value) INTO pieces FROM gratilo_monitor.payments(all_start,now(),false,'ALL') WHERE metric='tip_by_kind';
  IF pieces IS DISTINCT FROM tip THEN RAISE EXCEPTION 'Payment/transfer double count'; END IF;
  SELECT sum(value) INTO pieces FROM gratilo_monitor.payments(all_start,now(),true,'ALL') WHERE metric='tip_volume';
  IF pieces IS NULL OR pieces <= 0 OR pieces >= tip THEN RAISE EXCEPTION 'Stripe modes not separated'; END IF;
  IF (SELECT value FROM gratilo_monitor.payments(all_start,now(),false,'ALL') WHERE metric='tip_median') IS DISTINCT FROM 1150 THEN
    RAISE EXCEPTION 'Median wrong';
  END IF;
  IF (SELECT value FROM gratilo_monitor.payments(all_start,now(),false,'ALL') WHERE metric='revenue') IS DISTINCT FROM 3496 THEN
    RAISE EXCEPTION 'Incorrect evidenced net revenue or duplicated transfers';
  END IF;
  IF (SELECT value FROM gratilo_monitor.payments(all_start,now(),false,'ALL') WHERE metric='unknown_costs') IS DISTINCT FROM 33 THEN
    RAISE EXCEPTION 'Unknown costs must remain unknown';
  END IF;
  IF (SELECT value FROM gratilo_monitor.payments(all_start,now(),false,'POOL') WHERE metric='direct_application_fees') IS NOT NULL THEN
    RAISE EXCEPTION 'Pool SCT treated as Application Fee';
  END IF;
  IF (SELECT count(*) FROM gratilo_monitor.payments(all_start,now(),false,'ALL') WHERE metric='pricing_counts') <> 3 THEN
    RAISE EXCEPTION 'Historical pricing collapsed';
  END IF;
  IF NOT EXISTS(SELECT 1 FROM gratilo_monitor.payments(all_start,now(),false,'POOL') WHERE metric='transfer_pending' AND value>0)
    OR NOT EXISTS(SELECT 1 FROM gratilo_monitor.payments(all_start,now(),false,'ALL') WHERE metric='payout_failed' AND dimension='stripe_test' AND value>0)
    OR NOT EXISTS(SELECT 1 FROM gratilo_monitor.payments(all_start,now(),false,'ALL') WHERE metric='audit_status' AND dimension='INCOMPLETE' AND value>0)
    OR NOT EXISTS(SELECT 1 FROM gratilo_monitor.payments(all_start,now(),false,'ALL') WHERE metric='pending' AND value>0) THEN
    RAISE EXCEPTION 'Missing expected failure or pending statuses';
  END IF;
  IF (SELECT value FROM gratilo_monitor.users(all_start,now(),false) WHERE metric='memberships')
     <= (SELECT value FROM gratilo_monitor.users(all_start,now(),false) WHERE metric='members') THEN
     RAISE EXCEPTION 'Multi-pool memberships collapsed';
  END IF;
END
$$;
ROLLBACK;
