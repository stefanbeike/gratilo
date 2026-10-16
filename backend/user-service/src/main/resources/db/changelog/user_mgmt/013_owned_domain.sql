--liquibase formatted sql
--changeset gratilo:013-owned-domain context:@prd
-- The legacy .com domain is not owned by Gratilo. Preserve existing payment paths.
UPDATE user_mgmt.payment_url_pool
SET payment_url = 'https://gratilo.de' || substring(payment_url FROM length('https://gratilo.com') + 1)
WHERE payment_url LIKE 'https://gratilo.com/pay/%';

UPDATE user_mgmt.users
SET payment_url_path = 'https://gratilo.de' || substring(payment_url_path FROM length('https://gratilo.com') + 1)
WHERE payment_url_path LIKE 'https://gratilo.com/pay/%';
