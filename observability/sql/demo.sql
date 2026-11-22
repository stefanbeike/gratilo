\set ON_ERROR_STOP on
BEGIN;
-- Stable UUIDs are confined to the demo database. Seed once, preserving the fixture timeline on restart.
SELECT NOT EXISTS (SELECT 1 FROM user_mgmt.users WHERE email='fixture-1@example.invalid') AS populate \gset
\if :populate
INSERT INTO user_mgmt.users (id,email,password_hash,email_verified,created_at,updated_at)
SELECT md5('obs-user-'||n)::uuid,'fixture-'||n||'@example.invalid','!synthetic!',
       n%5<>0, now()-(n||' days')::interval,now()
FROM generate_series(1,60) n;

INSERT INTO user_mgmt.pools (id,owner_id,name,status,created_at,deleted_at,public_path)
SELECT md5('obs-pool-'||n)::uuid,md5('obs-user-'||n)::uuid,'Demo-Team '||n,
       CASE WHEN n%6=0 THEN 'DELETED' ELSE 'ACTIVE' END,now()-(n*3||' days')::interval,
       CASE WHEN n%6=0 THEN now()-interval '1 day' END,'team-demo-'||n
FROM generate_series(1,12) n;

INSERT INTO user_mgmt.pool_members
 (id,pool_id,user_id,share_percent,status,active,invitation_expires_at)
SELECT md5('obs-member-'||p||'-'||m)::uuid,md5('obs-pool-'||p)::uuid,
       md5('obs-user-'||m)::uuid,100::numeric/(p%5+2),
       CASE WHEN m=p%5+2 AND p%3=1 THEN 'INVITED' ELSE 'ACTIVE' END,true,now()+interval '8 hours'
FROM generate_series(1,12) p CROSS JOIN LATERAL generate_series(1,p%5+2) m;

INSERT INTO payment_mgmt.connected_accounts
 (id,user_id,stripe_account_id,onboarding_status,charges_enabled,transfers_enabled,payouts_enabled,
  details_submitted,stripe_status,status_checked_at,created_at)
SELECT md5('obs-connect-'||n)::uuid,md5('obs-user-'||n)::uuid,'acct_fixture'||n,
  CASE WHEN n%4=0 THEN 'SUBMITTED' ELSE 'COMPLETE' END,
  n%4<>0,n%4<>0,n%3<>0,n%4<>0,
  CASE WHEN n%4=0 THEN 'UNDER_REVIEW' WHEN n%7=0 THEN 'ACTION_REQUIRED' ELSE 'READY' END,
  CASE WHEN n%5=0 THEN now()-interval '3 days' ELSE now()-interval '3 hours' END,
  now()-(n*2||' days')::interval
FROM generate_series(1,32) n;

CREATE TEMP TABLE fixture_payments ON COMMIT DROP AS
SELECT n, md5('obs-tip-'||n)::uuid id, n%3=0 pool, n%29=0 live,
       CASE WHEN n%37=0 THEN 'USD' ELSE 'EUR' END currency,
       300+(n%18)*100 AS tip,
       CASE WHEN n%3=0 THEN 16 WHEN n%5=0 THEN 11 ELSE 15 END AS version,
       CASE WHEN n%11=0 THEN 'PENDING' WHEN n%7=0 THEN 'FAILED' WHEN n%41=0 THEN 'CANCELLED'
            WHEN n%23=0 THEN 'REFUNDED' WHEN n%31=0 THEN 'DISPUTED' ELSE 'BOOKED' END state,
       date_trunc('minute',now())-(n*7||' hours')::interval created,
       n%13<>0 AS complete
FROM generate_series(1,240) n;

INSERT INTO payment_mgmt.transactions
 (id,account_user_id,amount_minor,currency,type,status,provider,provider_reference,metadata,created_at,booked_at,updated_at,
  stripe_payment_intent_id,stripe_charge_id)
SELECT id,md5('obs-user-'||(n%20+1))::uuid,tip,currency,'TIP',state,'STRIPE',
       CASE WHEN n%17<>0 OR state NOT IN ('PENDING','FAILED','CANCELLED')
            THEN CASE WHEN live THEN 'cs_live_fixture' ELSE 'cs_test_fixture' END||n END,
       jsonb_build_object('tipAmountMinor',tip,'pricingVersion',version,'chargeModel',
         CASE WHEN pool THEN 'POOL' ELSE 'DIRECT' END,'feesCollector',
         CASE WHEN version=11 THEN 'stripe' ELSE 'application' END,'totalAmountMinor',tip+50,
         'estimatedStripeFeeMinor',60,'feeCovered',true)
       || CASE WHEN pool THEN jsonb_build_object('poolId',md5('obs-pool-'||(n%12+1))::uuid) ELSE '{}'::jsonb END,
       created,CASE WHEN state IN ('BOOKED','REFUNDED','DISPUTED') THEN created+make_interval(secs=>30+n%180) END,
       created+interval '5 minutes',
       CASE WHEN state<>'PENDING' THEN 'pi_fixture'||n END,CASE WHEN state<>'PENDING' THEN 'ch_fixture'||n END
FROM fixture_payments;

INSERT INTO payment_mgmt.payment_fee_audits
 (transaction_id,charged_minor,surcharge_minor,estimated_fee_minor,estimate_percent,estimate_fixed_minor,estimate_basis,
 status,pricing_version,platform_fee_minor,platform_fee_net_minor,platform_fee_tax_minor,actual_fee_minor,
 platform_net_revenue_minor,stripe_charge_id,checked_at)
SELECT id,tip+50,50,60,3.15,25,'SYNTHETIC_FIXTURE',
       CASE WHEN state IN ('FAILED','CANCELLED') THEN 'NOT_APPLICABLE'
            WHEN complete AND state<>'PENDING' THEN 'DIFFERENCE' ELSE 'PENDING' END,
       version,tip/20+45,(tip/20+45)-round((tip/20+45)*19::numeric/119),round((tip/20+45)*19::numeric/119),
       CASE WHEN complete AND state IN ('BOOKED','REFUNDED','DISPUTED') THEN 56+n%12 END,
       CASE WHEN complete AND state='BOOKED' THEN 20+n%18 END,
       CASE WHEN state<>'PENDING' THEN 'ch_fixture'||n END,created+interval '5 minutes'
FROM fixture_payments;

INSERT INTO payment_mgmt.stripe_payment_audit
 (payment_id,payment_type,livemode,platform_account_id,charge_account_id,checkout_session_id,payment_intent_id,
  charge_id,balance_transaction_id,currency,expected_gross_minor,recipient_target_minor,status,financial_data,revision,
  created_at,checked_at,next_check_at)
SELECT f.id,CASE WHEN pool THEN 'POOL' ELSE 'SINGLE' END,live,'acct_fixture_platform',
       CASE WHEN NOT pool THEN 'acct_fixture_direct' END,t.provider_reference,t.stripe_payment_intent_id,t.stripe_charge_id,
       CASE WHEN complete AND state IN ('BOOKED','REFUNDED','DISPUTED') THEN 'txn_fixture'||n END,
       f.currency,tip+50,tip-tip/20-45,
       CASE WHEN state='PENDING' AND n%2=0 THEN 'PENDING'
            WHEN n%97=0 THEN 'REVIEW_REQUIRED'
            WHEN NOT complete OR state IN ('PENDING','FAILED','CANCELLED') OR pool AND n%4=0 THEN 'INCOMPLETE'
            ELSE 'COMPLETE' END,
       jsonb_build_object(
         'paymentIntent',jsonb_build_object('status',CASE state WHEN 'PENDING' THEN 'requires_payment_method'
           WHEN 'FAILED' THEN 'requires_payment_method' WHEN 'CANCELLED' THEN 'canceled' ELSE 'succeeded' END),
         'checkout',jsonb_build_object('status',CASE state WHEN 'PENDING' THEN 'open' WHEN 'FAILED' THEN 'expired' ELSE 'complete' END),
         'refundedMinor',CASE WHEN state='REFUNDED' THEN tip+50 ELSE 0 END,
         'refunds',CASE WHEN state='REFUNDED' THEN jsonb_build_array(jsonb_build_object(
           'amount',tip+50,'created',extract(epoch FROM created+interval '1 hour')::bigint,'status','succeeded')) ELSE '[]'::jsonb END,
         'disputes',CASE WHEN state='DISPUTED' THEN jsonb_build_array(jsonb_build_object(
           'amount',tip+50,'created',extract(epoch FROM created+interval '1 hour')::bigint,'status','needs_response')) ELSE '[]'::jsonb END,
         'platformBorneCostsMinor',56+n%12,
         'platformResidualMinor',20+n%18+round((tip/20+45)*19::numeric/119),
         'missingData',CASE WHEN pool AND n%4=0 THEN '["POOL_DISTRIBUTION_PENDING"]'::jsonb
           WHEN NOT complete THEN '["PAYMENT_BALANCE_MISSING"]'::jsonb ELSE '[]'::jsonb END)
       || CASE WHEN NOT pool THEN jsonb_build_object('applicationFee',jsonb_build_object('amount',tip/20+45)) ELSE '{}'::jsonb END,
       1,created,created+interval '10 minutes',
       CASE WHEN NOT complete AND n%5=0 THEN created+interval '1 hour' ELSE now()+interval '45 minutes' END
FROM fixture_payments f JOIN payment_mgmt.transactions t ON t.id=f.id;

INSERT INTO payment_mgmt.pool_transfers
 (id,transaction_id,recipient_user_id,stripe_account_id,share_percent,amount_minor,status,stripe_transfer_id,
  settlement_approved,created_at,updated_at,first_attempt_at,last_attempt_at,failure_code,funds_available_at)
SELECT md5('obs-allocation-'||n||'-'||m)::uuid,id,md5('obs-user-'||(m+n%20))::uuid,
  'acct_fixture'||(m+n%20),50,(tip-tip/20-45)/2+CASE WHEN m=1 THEN (tip-tip/20-45)%2 ELSE 0 END,
  CASE WHEN state='BOOKED' AND n%4<>0 THEN 'TRANSFERRED' ELSE 'PENDING' END,
  CASE WHEN state='BOOKED' AND n%4<>0 THEN 'tr_fixture'||n||'x'||m END,
  n%8<>0,created,created+interval '1 hour',created+interval '30 minutes',created+interval '1 hour',
  CASE WHEN state<>'BOOKED' THEN 'WAITING_CONFIRMED_PAYMENT'
       WHEN n%8=0 THEN 'APPROVAL_REQUIRED' WHEN n%4=0 THEN 'RECIPIENT_NOT_READY' END,
  created+interval '45 minutes'
FROM fixture_payments CROSS JOIN generate_series(1,2) m WHERE pool;

INSERT INTO payment_mgmt.stripe_payment_transfer_audit
 (payment_id,transfer_id,livemode,destination_account_id,source_charge_id,amount_minor,reversed_minor,
  currency,balance_transaction_id,destination_payment_id,recipient_balance_transaction_id,financial_data,observed_revision)
SELECT p.transaction_id,p.stripe_transfer_id,f.live,p.stripe_account_id,'ch_fixture'||f.n,p.amount_minor,0,
       f.currency,'txn_platform_'||p.stripe_transfer_id,'py_'||p.stripe_transfer_id,'txn_recipient_'||p.stripe_transfer_id,
       jsonb_build_object('created',extract(epoch FROM p.updated_at)::bigint,'reversals','[]'::jsonb),1
FROM payment_mgmt.pool_transfers p JOIN fixture_payments f ON f.id=p.transaction_id WHERE p.stripe_transfer_id IS NOT NULL;

INSERT INTO payment_mgmt.stripe_payment_audit_event (payment_id,event_key,event_type,financial_data,recorded_at)
SELECT id,'stripe:synthetic-'||n,'payment_intent.succeeded','{}',created+interval '5 minutes'
FROM fixture_payments WHERE state='BOOKED';

INSERT INTO payment_mgmt.transactions
 (id,account_user_id,amount_minor,currency,type,status,provider,metadata,created_at,booked_at,updated_at)
SELECT md5('obs-payout-'||n)::uuid,md5('obs-user-'||n)::uuid,-n*140,'EUR','PAYOUT',
       CASE WHEN n%6=0 THEN 'FAILED' WHEN n%3=0 THEN 'PENDING' ELSE 'BOOKED' END,
       CASE WHEN n%2=0 AND n%6<>0 THEN 'STRIPE_SANDBOX' ELSE 'STRIPE' END,
       jsonb_build_object('payoutVersion',1,'simulation',n%2=0 AND n%6<>0,'stripePayoutFeeKnown',false,
         'payoutStatus',CASE WHEN n%6=0 THEN 'failed' WHEN n%3=0 THEN 'in_transit' ELSE 'paid' END),
       now()-(n*6||' hours')::interval,
       CASE WHEN n%3<>0 THEN now()-(n*6-5||' hours')::interval END,
       CASE WHEN n=6 THEN now()-interval '20 minutes' ELSE now()-(n*6-5||' hours')::interval END
FROM generate_series(1,25) n;
\endif
COMMIT;
