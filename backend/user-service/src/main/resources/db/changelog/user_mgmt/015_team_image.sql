--liquibase formatted sql
--changeset gratilo:015-team-image
ALTER TABLE user_mgmt.pools ADD COLUMN profile_image TEXT;
