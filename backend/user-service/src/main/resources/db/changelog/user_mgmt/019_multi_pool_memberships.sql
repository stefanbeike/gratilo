--liquibase formatted sql
--changeset gratilo:019-multi-pool-memberships
-- A user may own any number of independent pools. Existing IDs and payment URLs stay unchanged.
DROP INDEX IF EXISTS user_mgmt.pools_owner_unique;
ALTER TABLE user_mgmt.pools ADD COLUMN IF NOT EXISTS public_path VARCHAR(120);
UPDATE user_mgmt.pools SET public_path='team-' || replace(id::text,'-','') WHERE public_path IS NULL;
ALTER TABLE user_mgmt.pools ALTER COLUMN public_path SET NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS pools_public_path_unique ON user_mgmt.pools(public_path);
--rollback DROP INDEX IF EXISTS user_mgmt.pools_public_path_unique;
--rollback ALTER TABLE user_mgmt.pools DROP COLUMN IF EXISTS public_path;
