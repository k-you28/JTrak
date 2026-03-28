-- V3: Remove email verification feature.
-- Drops the email_verification_tokens table and the email_verified column from users.

DROP TABLE IF EXISTS email_verification_tokens;

ALTER TABLE users DROP COLUMN IF EXISTS email_verified;
