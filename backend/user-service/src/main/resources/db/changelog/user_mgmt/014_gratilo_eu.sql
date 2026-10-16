--liquibase formatted sql
--changeset gratilo:014-gratilo-eu context:@prd
-- Keep assigned payment paths while moving legacy website domains to gratilo.eu.
UPDATE user_mgmt.payment_url_pool
SET payment_url = regexp_replace(payment_url, '^https://gratilo[.](de|com)/pay/', 'https://gratilo.eu/pay/')
WHERE payment_url ~ '^https://gratilo[.](de|com)/pay/';

UPDATE user_mgmt.users
SET payment_url_path = regexp_replace(payment_url_path, '^https://gratilo[.](de|com)/pay/', 'https://gratilo.eu/pay/')
WHERE payment_url_path ~ '^https://gratilo[.](de|com)/pay/';
