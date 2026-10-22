--liquibase formatted sql
--changeset gratilo:015-manual-payout-safety
-- Existing payouts are excluded; historical requests and their amounts are unchanged.
CREATE UNIQUE INDEX manual_payout_active_account_idx
 ON payment_mgmt.transactions(account_user_id)
 WHERE type='PAYOUT' AND metadata->>'payoutVersion'='1' AND status IN ('PENDING','AUTHORIZED');
CREATE UNIQUE INDEX manual_payout_provider_reference_idx
 ON payment_mgmt.transactions(provider_reference)
 WHERE type='PAYOUT' AND metadata->>'payoutVersion'='1' AND provider_reference IS NOT NULL;
--rollback DROP INDEX payment_mgmt.manual_payout_provider_reference_idx;
--rollback DROP INDEX payment_mgmt.manual_payout_active_account_idx;
