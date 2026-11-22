\set ON_ERROR_STOP on
BEGIN;
CREATE SCHEMA IF NOT EXISTS gratilo_monitor;
REVOKE ALL ON SCHEMA gratilo_monitor FROM PUBLIC;
REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA gratilo_monitor FROM PUBLIC;

-- Internal projection. Neither Grafana nor the exporter can select this view.
CREATE OR REPLACE VIEW gratilo_monitor.payment_rows AS
SELECT t.id, t.account_user_id, t.created_at, t.booked_at, t.status, t.currency, t.amount_minor,
       CASE WHEN a.payment_id IS NOT NULL THEN a.livemode
            WHEN left(t.provider_reference, 8) = 'cs_test_' THEN false
            WHEN left(t.provider_reference, 8) = 'cs_live_' THEN true END AS livemode,
       COALESCE(a.payment_type, CASE
         WHEN t.metadata ? 'poolId' OR t.metadata->>'chargeModel' = 'POOL'
           OR t.metadata->>'pricingVersion' IN ('5','7','10')
           OR EXISTS (SELECT 1 FROM payment_mgmt.pool_transfers p WHERE p.transaction_id=t.id) THEN 'POOL'
         ELSE 'SINGLE' END) AS kind,
       COALESCE(t.metadata->>'pricingVersion', 'legacy') AS pricing,
       CASE WHEN t.metadata->>'chargeModel' = 'POOL' OR a.payment_type = 'POOL' THEN 'platform_sct'
            WHEN t.metadata->>'feesCollector' = 'stripe' THEN 'connected'
            WHEN t.metadata->>'feesCollector' = 'application' THEN 'platform_direct'
            ELSE 'unknown' END AS bearer,
       t.provider_reference IS NOT NULL AS session_created,
       a.payment_intent_id IS NOT NULL AS intent_observed, a.charge_id IS NOT NULL AS charge_observed,
       a.financial_data::jsonb AS evidence, a.status AS audit_status, a.created_at AS enrolled_at,
       a.checked_at, a.next_check_at, a.revision,
       f.charged_minor, f.platform_fee_minor, f.platform_fee_net_minor, f.platform_fee_tax_minor,
       f.estimated_fee_minor, f.status AS fee_status,
       CASE WHEN a.status = 'COMPLETE' AND a.checked_at IS NOT NULL AND f.status IN ('MATCHED','DIFFERENCE')
                   AND f.last_error IS NULL AND a.charge_id=f.stripe_charge_id
            THEN f.actual_fee_minor END AS actual_processing_minor,
       CASE WHEN a.status = 'COMPLETE' AND a.checked_at IS NOT NULL AND f.status IN ('MATCHED','DIFFERENCE')
                   AND f.last_error IS NULL AND a.charge_id=f.stripe_charge_id
                   AND t.status IN ('BOOKED','COMPLETED')
                   AND (a.financial_data::jsonb->>'refundedMinor')::bigint = 0
                   AND a.financial_data::jsonb->'disputes' = '[]'::jsonb
                   AND f.platform_net_revenue_minor IS NOT NULL
            THEN (a.financial_data::jsonb->>'platformResidualMinor')::numeric - f.platform_fee_tax_minor END AS verified_revenue_minor,
       (a.status = 'COMPLETE' AND a.checked_at IS NOT NULL) AS complete
FROM payment_mgmt.transactions t
LEFT JOIN payment_mgmt.stripe_payment_audit a ON a.payment_id=t.id AND a.currency=t.currency
LEFT JOIN payment_mgmt.payment_fee_audits f ON f.transaction_id=t.id
WHERE t.type='TIP' AND t.provider='STRIPE' AND t.amount_minor > 0;

CREATE OR REPLACE FUNCTION gratilo_monitor.payments(since timestamptz, until timestamptz, live boolean, model text)
RETURNS TABLE(metric text, dimension text, value numeric)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
WITH base AS MATERIALIZED (
  SELECT * FROM gratilo_monitor.payment_rows p
  WHERE p.currency='EUR' AND p.livemode=live AND (model='ALL' OR p.kind=model)
), attempts AS (
  SELECT * FROM base WHERE created_at>=since AND created_at<until
), booked AS (
  SELECT * FROM base WHERE booked_at>=since AND booked_at<until
      AND status IN ('BOOKED','COMPLETED','REFUNDED','DISPUTED','CHARGEBACK')
), transfers AS (
  SELECT b.complete, p.*
  FROM payment_mgmt.pool_transfers p JOIN base b ON b.id=p.transaction_id
  WHERE b.kind='POOL' AND b.booked_at IS NOT NULL
), payout_rows AS (
  SELECT t.*, CASE WHEN provider='STRIPE_SANDBOX' THEN 'simulation' ELSE 'stripe_test' END AS scope
  FROM payment_mgmt.transactions t
  WHERE NOT live AND type='PAYOUT' AND currency='EUR' AND amount_minor<0
    AND provider IN ('STRIPE','STRIPE_SANDBOX') AND metadata->>'payoutVersion'='1'
), finance AS (
  SELECT count(*) n, sum(amount_minor) volume, avg(amount_minor) mean,
    (SELECT avg(m.amount_minor) FROM (SELECT b.amount_minor,
        row_number() OVER (ORDER BY b.amount_minor) AS position, count(*) OVER () AS total FROM booked b) m
        WHERE m.position IN ((m.total+1)/2,(m.total+2)/2)) median,
    sum(charged_minor) gross, sum(platform_fee_minor) commission, sum(platform_fee_net_minor) service_net,
    sum(platform_fee_tax_minor) tax, sum(verified_revenue_minor) revenue,
    sum(actual_processing_minor) observed,
    sum(estimated_fee_minor) FILTER (WHERE actual_processing_minor IS NOT NULL) comparable_estimate,
    sum(actual_processing_minor::numeric-estimated_fee_minor) AS variance,
    count(*) FILTER (WHERE NOT COALESCE(complete,false)) gaps,
    count(*) FILTER (WHERE actual_processing_minor IS NULL) unknown_costs,
    count(*) FILTER (WHERE verified_revenue_minor IS NULL) unknown_revenue,
    avg(verified_revenue_minor) revenue_mean,
    sum(platform_fee_minor) / NULLIF(sum(amount_minor) FILTER (WHERE platform_fee_minor IS NOT NULL),0) AS commission_rate,
    avg(extract(epoch FROM booked_at-created_at)) FILTER (WHERE booked_at>=created_at) booking_seconds,
    sum((evidence->>'platformBorneCostsMinor')::bigint) FILTER (WHERE complete) platform_costs
  FROM booked
), funnel AS (
  SELECT count(*) AS attempts, count(*) FILTER (WHERE session_created) sessions,
    count(*) FILTER (WHERE session_created AND booked_at IS NOT NULL AND booked_at<until
      AND status IN ('BOOKED','COMPLETED','REFUNDED','DISPUTED','CHARGEBACK')) successes,
    count(*) FILTER (WHERE status='FAILED') failed,
    count(*) FILTER (WHERE status='CANCELLED') cancelled,
    count(*) FILTER (WHERE status IN ('PENDING','AUTHORIZED')) pending,
    count(*) FILTER (WHERE evidence->'checkout'->>'status'='expired') expired,
    count(*) FILTER (WHERE intent_observed) intents,
    count(*) FILTER (WHERE charge_observed) charges,
    count(*) FILTER (WHERE EXISTS (
        SELECT 1 FROM payment_mgmt.stripe_payment_audit_event e
        WHERE e.payment_id=attempts.id AND e.event_key LIKE 'stripe:%' AND e.recorded_at<until
    )) with_webhooks
  FROM attempts
), transfer_counts AS (
  SELECT count(*) FILTER (WHERE created_at>=since AND created_at<until) allocated,
    count(*) FILTER (WHERE stripe_transfer_id IS NOT NULL AND status IN ('TRANSFERRED','REVERSAL_PENDING','REVERSED')) confirmed,
    count(*) FILTER (WHERE status='PENDING' AND stripe_transfer_id IS NULL) pending,
    count(*) FILTER (WHERE status='PENDING' AND stripe_transfer_id IS NULL AND failure_code IN
       ('APPROVAL_REQUIRED','RECIPIENT_NOT_READY','WAITING_CONFIRMED_PAYMENT','REFUND_RECONCILIATION_REQUIRED')) held,
    count(*) FILTER (WHERE status='PENDING' AND failure_code IS NOT NULL AND failure_code NOT IN
       ('APPROVAL_REQUIRED','RECIPIENT_NOT_READY','WAITING_CONFIRMED_PAYMENT','REFUND_RECONCILIATION_REQUIRED','TRANSFER_SUBMITTING')) retry_review,
    max(extract(epoch FROM now()-created_at)) FILTER (WHERE status='PENDING' AND stripe_transfer_id IS NULL) oldest,
    sum(greatest(0,amount_minor::numeric-reversal_amount_minor)) FILTER (WHERE status='PENDING' AND stripe_transfer_id IS NULL) pending_minor,
    sum(greatest(0,reversal_amount_minor::numeric-reversed_amount_minor)) FILTER (WHERE stripe_transfer_id IS NOT NULL) reversal_minor,
    count(*) FILTER (WHERE status='PENDING' AND failure_code='RECIPIENT_NOT_READY') readiness
  FROM transfers
), refund_events AS (
  SELECT b.complete, item
  FROM base b CROSS JOIN LATERAL jsonb_array_elements(b.evidence->'refunds') r(item)
  WHERE b.complete AND item->>'status'='succeeded'
    AND to_timestamp((item->>'created')::bigint)>=since AND to_timestamp((item->>'created')::bigint)<until
), recipient_receipts AS (
  SELECT b.account_user_id recipient, b.booked_at FROM base b WHERE b.kind='SINGLE' AND b.booked_at IS NOT NULL
  UNION ALL
  SELECT p.recipient_user_id, to_timestamp((ta.financial_data::jsonb->>'created')::bigint)
  FROM transfers p JOIN base b ON b.id=p.transaction_id
  JOIN payment_mgmt.stripe_payment_transfer_audit ta
    ON ta.payment_id=b.id AND ta.transfer_id=p.stripe_transfer_id AND ta.livemode=live
    AND ta.currency=b.currency AND ta.observed_revision=b.revision
  WHERE p.complete AND ta.recipient_balance_transaction_id IS NOT NULL
), receipt_counts AS (
 SELECT count(*) FILTER (WHERE first_at>=since AND first_at<until) first_count,
        count(*) FILTER (WHERE inside>=2) repeat_count
 FROM (SELECT min(booked_at) first_at, count(*) FILTER (WHERE booked_at>=since AND booked_at<until) inside
       FROM recipient_receipts WHERE booked_at IS NOT NULL GROUP BY recipient) r
)
SELECT v.metric,'total',v.value FROM finance f CROSS JOIN funnel u CROSS JOIN transfer_counts t CROSS JOIN receipt_counts r
CROSS JOIN LATERAL (VALUES
 ('tip_volume',COALESCE(f.volume,0)),('booked_count',f.n),('tip_mean',f.mean),('tip_median',f.median),
 ('gross',f.gross),('commission',f.commission),('service_net',f.service_net),('tax',f.tax),
 ('revenue',f.revenue),('stripe_processing',f.observed),('paired_estimate',f.comparable_estimate),('fee_variance',f.variance),
 ('audit_gaps',f.gaps),('unknown_costs',f.unknown_costs),('unknown_revenue',f.unknown_revenue),
 ('revenue_mean',f.revenue_mean),('commission_rate',f.commission_rate),('booking_seconds',f.booking_seconds),
 ('platform_stripe_costs',f.platform_costs),('checkout_attempts',u.attempts),('checkout_sessions',u.sessions),
 ('checkout_rate',u.successes::numeric/NULLIF(u.sessions,0)),('failed',u.failed),('cancelled',u.cancelled),('pending',u.pending),
 ('expired',u.expired),('intent_observed',u.intents),('charge_observed',u.charges),('webhook_linked',u.with_webhooks),('allocations',t.allocated),('transfer_confirmed',t.confirmed),('transfer_pending',t.pending),
 ('transfer_held',t.held),('transfer_retry_review',t.retry_review),('transfer_oldest',t.oldest),
 ('transfer_pending_minor',t.pending_minor),('reversal_pending_minor',t.reversal_minor),('pool_readiness_blocks',t.readiness),
 ('first_receipt',r.first_count),('repeat_receipts',r.repeat_count)
) v(metric,value)
UNION ALL SELECT 'tip_by_kind', kind, sum(amount_minor) FROM booked GROUP BY kind
UNION ALL SELECT 'pricing_counts', pricing, count(*) FROM booked GROUP BY pricing
UNION ALL SELECT 'revenue_by_pricing', pricing, sum(verified_revenue_minor) FROM booked GROUP BY pricing
UNION ALL SELECT 'revenue_by_kind', kind, sum(verified_revenue_minor) FROM booked GROUP BY kind
UNION ALL SELECT 'fees_by_bearer', bearer, sum(actual_processing_minor) FROM booked GROUP BY bearer
UNION ALL SELECT 'direct_application_fees','total',sum((evidence->'applicationFee'->>'amount')::bigint)
  FROM booked WHERE complete AND kind='SINGLE'
UNION ALL SELECT 'tx_status',status,count(*) FROM attempts GROUP BY status
UNION ALL SELECT 'intent_status',evidence->'paymentIntent'->>'status', count(*) FROM attempts
  WHERE evidence->'paymentIntent'->>'status' IS NOT NULL GROUP BY 2
UNION ALL SELECT 'audit_status',audit_status,count(*) FROM base WHERE audit_status IS NOT NULL GROUP BY audit_status
UNION ALL SELECT 'oldest_audit','total',max(extract(epoch FROM now()-enrolled_at)) FROM base
  WHERE booked_at IS NOT NULL AND audit_status<>'COMPLETE'
UNION ALL SELECT 'audit_lag','total',max(greatest(0,extract(epoch FROM now()-next_check_at))) FROM base WHERE next_check_at IS NOT NULL
UNION ALL SELECT 'audit_missing',gap,count(*) FROM base
  CROSS JOIN LATERAL jsonb_array_elements_text(evidence->'missingData') m(gap)
  WHERE audit_status<>'COMPLETE' GROUP BY gap
UNION ALL SELECT 'refund_count','total',CASE WHEN EXISTS (SELECT 1 FROM base WHERE complete) THEN count(*) END FROM refund_events
UNION ALL SELECT 'refund_volume','total',CASE WHEN EXISTS (SELECT 1 FROM base WHERE complete)
  THEN coalesce(sum((item->>'amount')::bigint),0) END FROM refund_events
UNION ALL SELECT 'dispute_count','total',count(*) FROM base b CROSS JOIN LATERAL jsonb_array_elements(b.evidence->'disputes') d(item)
  WHERE b.complete AND to_timestamp((item->>'created')::bigint)>=since AND to_timestamp((item->>'created')::bigint)<until
  HAVING EXISTS (SELECT 1 FROM base WHERE complete)
UNION ALL SELECT 'transfer_reversals','total',count(*) FROM base b
  JOIN payment_mgmt.stripe_payment_transfer_audit ta ON ta.payment_id=b.id AND ta.livemode=live
    AND ta.currency=b.currency AND ta.observed_revision=b.revision
  CROSS JOIN LATERAL jsonb_array_elements(ta.financial_data::jsonb->'reversals') r(item)
  WHERE b.complete AND to_timestamp((item->>'created')::bigint)>=since AND to_timestamp((item->>'created')::bigint)<until
  HAVING EXISTS (SELECT 1 FROM base WHERE complete AND kind='POOL')
UNION ALL SELECT 'settlement_seconds','total',avg(extract(epoch FROM
    to_timestamp((a.financial_data::jsonb->>'created')::bigint)-p.created_at))
  FROM transfers p JOIN base b ON b.id=p.transaction_id
  JOIN payment_mgmt.stripe_payment_transfer_audit a ON a.payment_id=b.id AND a.transfer_id=p.stripe_transfer_id
       AND a.livemode=live AND a.currency=b.currency AND a.observed_revision=b.revision
  WHERE b.complete AND to_timestamp((a.financial_data::jsonb->>'created')::bigint)>=greatest(since,p.created_at)
    AND to_timestamp((a.financial_data::jsonb->>'created')::bigint)<until
UNION ALL SELECT 'payout_status',scope||' / '||COALESCE(metadata->>'payoutStatus',lower(status)),count(*)
  FROM payout_rows WHERE created_at>=since AND created_at<until GROUP BY 2
UNION ALL SELECT 'payout_requested',scope,count(*) FROM payout_rows WHERE created_at>=since AND created_at<until GROUP BY scope
UNION ALL SELECT 'payout_open',scope,count(*) FROM payout_rows WHERE status IN ('PENDING','AUTHORIZED') GROUP BY scope
UNION ALL SELECT 'payout_failed',scope,count(*) FROM payout_rows
  WHERE status='FAILED' AND updated_at>=since AND updated_at<until GROUP BY scope
UNION ALL SELECT 'payout_paid_amount',scope,sum(-amount_minor::numeric) FROM payout_rows
  WHERE status='BOOKED' AND metadata->>'payoutStatus'='paid' AND booked_at>=since AND booked_at<until GROUP BY scope
UNION ALL SELECT 'payout_seconds',scope,avg(extract(epoch FROM booked_at-created_at)) FROM payout_rows
  WHERE status='BOOKED' AND booked_at>=greatest(since,created_at) AND booked_at<until GROUP BY scope
UNION ALL SELECT 'payout_oldest',scope,max(extract(epoch FROM now()-created_at)) FROM payout_rows
  WHERE status IN ('PENDING','AUTHORIZED') GROUP BY scope
UNION ALL SELECT 'payout_unknown_fees',scope,count(*) FROM payout_rows
  WHERE metadata->>'stripePayoutFeeKnown' IS DISTINCT FROM 'true' AND created_at>=since AND created_at<until GROUP BY scope;
$$;

CREATE OR REPLACE FUNCTION gratilo_monitor.volume(since timestamptz, until timestamptz, live boolean, model text, grain text)
RETURNS TABLE("time" timestamptz, tip_minor numeric, commission_ratio numeric)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
SELECT date_trunc(grain,p.booked_at,'UTC'),sum(p.amount_minor),
  sum(p.platform_fee_minor)/NULLIF(sum(p.amount_minor) FILTER (WHERE p.platform_fee_minor IS NOT NULL),0)
FROM gratilo_monitor.payment_rows p
WHERE grain IN ('day','week','month') AND p.livemode=live AND p.currency='EUR' AND (model='ALL' OR p.kind=model)
  AND p.booked_at>=since AND p.booked_at<until AND p.status IN ('BOOKED','COMPLETED','REFUNDED','DISPUTED','CHARGEBACK')
GROUP BY 1 ORDER BY 1;
$$;

CREATE OR REPLACE FUNCTION gratilo_monitor.users(since timestamptz, until timestamptz, live boolean)
RETURNS TABLE(metric text, dimension text, value numeric)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
WITH user_stats AS (
  SELECT count(*) FILTER (WHERE created_at>=since AND created_at<until) registrations,
    count(*) FILTER (WHERE email_verified AND deleted_at IS NULL) verified_users
  FROM user_mgmt.users
), pools AS (
  SELECT count(*) total, count(*) FILTER (WHERE status='ACTIVE') active,
    count(*) FILTER (WHERE status='DELETED') deleted,
    count(*) FILTER (WHERE created_at>=since AND created_at<until) new_pools
  FROM user_mgmt.pools
), pool_admins AS (
  SELECT count(*) amount FROM (SELECT owner_id FROM user_mgmt.pools GROUP BY owner_id
   HAVING min(created_at)>=since AND min(created_at)<until) owners
), members AS (
  SELECT count(*) FILTER (WHERE m.status='ACTIVE') memberships,
    count(DISTINCT m.user_id) FILTER (WHERE m.status='ACTIVE') people,
    count(*) FILTER (WHERE m.status='INVITED' AND m.invitation_expires_at>now()) invites
  FROM user_mgmt.pool_members m JOIN user_mgmt.pools p ON p.id=m.pool_id
  JOIN user_mgmt.users u ON u.id=m.user_id
  WHERE p.status='ACTIVE' AND m.active AND m.ended_at IS NULL AND u.deleted_at IS NULL
), connect AS (
  SELECT count(*) total, count(*) FILTER (WHERE onboarding_status='COMPLETE') onboarded,
    count(*) FILTER (WHERE charges_enabled) charges, count(*) FILTER (WHERE transfers_enabled) transfers,
    count(*) FILTER (WHERE payouts_enabled) payouts,
    count(*) FILTER (WHERE status_checked_at IS NULL OR status_checked_at<now()-interval '24 hours') stale,
    count(*) FILTER (WHERE stripe_status='ACTION_REQUIRED') action,
    count(*) FILTER (WHERE stripe_status='UNDER_REVIEW') review,
    count(*) FILTER (WHERE status_checked_at IS NOT NULL AND NOT payouts_enabled) payout_restricted,
    count(*) FILTER (WHERE onboarding_status='COMPLETE' AND charges_enabled AND transfers_enabled AND payouts_enabled) fully_ready
  FROM payment_mgmt.connected_accounts WHERE stripe_account_id IS NOT NULL
)
SELECT s.metric,'total',s.value FROM user_stats u CROSS JOIN pools p CROSS JOIN pool_admins a CROSS JOIN members m
CROSS JOIN LATERAL (VALUES ('registrations',u.registrations),('verified_users',u.verified_users),
 ('pools_all',p.total),('pools_active',p.active),('pools_deleted',p.deleted),('pools_new',p.new_pools),
 ('admins_new',a.amount),('memberships',m.memberships),('members',m.people),('invitations',m.invites)) s(metric,value)
UNION ALL
SELECT 'members_distribution',distribution.band,count(*) FROM (
 SELECT CASE WHEN count(u.id)<=1 THEN '0–1' WHEN count(u.id)<=4 THEN '2–4' WHEN count(u.id)<=9 THEN '5–9' ELSE '10+' END band
 FROM user_mgmt.pools p LEFT JOIN user_mgmt.pool_members m ON m.pool_id=p.id AND m.active AND m.status='ACTIVE' AND m.ended_at IS NULL
 LEFT JOIN user_mgmt.users u ON u.id=m.user_id AND u.deleted_at IS NULL
 WHERE p.status='ACTIVE' GROUP BY p.id
) distribution GROUP BY distribution.band
UNION ALL
SELECT s.metric,'total',s.value FROM connect c CROSS JOIN LATERAL (VALUES
 ('connected',c.total),('onboarded',c.onboarded),('charges',c.charges),('transfers',c.transfers),('payouts',c.payouts),
 ('stale',c.stale),('needs_action',c.action),('under_review',c.review),('payout_restricted',c.payout_restricted),
 ('fully_ready',c.fully_ready)) s(metric,value) WHERE NOT live
UNION ALL
SELECT 'connect_status',stripe_status,count(*) FROM payment_mgmt.connected_accounts
 WHERE NOT live AND stripe_account_id IS NOT NULL GROUP BY stripe_status;
$$;

CREATE OR REPLACE FUNCTION gratilo_monitor.registrations(since timestamptz, until timestamptz, grain text)
RETURNS TABLE("time" timestamptz, registrations bigint)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
SELECT date_trunc(grain,u.created_at,'UTC'), count(*) FROM user_mgmt.users u
WHERE grain IN ('day','week','month') AND u.created_at>=since AND u.created_at<until GROUP BY 1 ORDER BY 1;
$$;

-- Alert gauges use backlog age at evaluation time and never rely on new business activity.
CREATE OR REPLACE FUNCTION gratilo_monitor.operations()
RETURNS TABLE(livemode text, audit_overdue bigint, audit_review bigint, pool_overdue bigint, payout_failed bigint)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
SELECT modes.name,
   (SELECT count(*) FROM payment_mgmt.stripe_payment_audit a JOIN payment_mgmt.transactions t ON t.id=a.payment_id
     WHERE a.livemode=modes.live AND t.booked_at IS NOT NULL AND a.next_check_at<now()-interval '15 minutes'),
   (SELECT count(*) FROM payment_mgmt.stripe_payment_audit a
     WHERE a.livemode=modes.live AND a.status='REVIEW_REQUIRED'),
   (SELECT count(*) FROM payment_mgmt.pool_transfers p JOIN gratilo_monitor.payment_rows t ON t.id=p.transaction_id
     WHERE t.livemode=modes.live AND t.status IN ('BOOKED','COMPLETED') AND t.booked_at IS NOT NULL
       AND p.status='PENDING' AND p.stripe_transfer_id IS NULL AND p.settlement_approved
       AND greatest(t.booked_at,p.created_at,coalesce(p.funds_available_at,t.booked_at))<now()-interval '24 hours'),
   (SELECT count(*) FROM payment_mgmt.transactions t WHERE NOT modes.live AND t.type='PAYOUT'
     AND t.provider='STRIPE' AND t.metadata->>'payoutVersion'='1' AND t.status='FAILED'
     AND t.updated_at>=now()-interval '1 hour')
FROM (VALUES ('test',false),('live',true)) modes(name,live);
$$;
CREATE OR REPLACE FUNCTION gratilo_monitor.environment() RETURNS text
LANGUAGE sql STABLE SET search_path = pg_catalog, pg_temp AS $$
SELECT current_setting('gratilo_monitor.environment',true)
$$;
REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA gratilo_monitor FROM PUBLIC;
GRANT USAGE ON SCHEMA gratilo_monitor TO gratilo_grafana, gratilo_pg_metrics;
GRANT EXECUTE ON FUNCTION gratilo_monitor.payments(timestamptz,timestamptz,boolean,text),
  gratilo_monitor.volume(timestamptz,timestamptz,boolean,text,text),
  gratilo_monitor.users(timestamptz,timestamptz,boolean),
  gratilo_monitor.registrations(timestamptz,timestamptz,text), gratilo_monitor.environment() TO gratilo_grafana;
GRANT EXECUTE ON FUNCTION gratilo_monitor.operations() TO gratilo_pg_metrics;
COMMIT;
