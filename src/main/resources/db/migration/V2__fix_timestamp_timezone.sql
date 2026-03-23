-- V2: Convert all TIMESTAMP columns to TIMESTAMP WITH TIME ZONE.
-- Hibernate 6 maps java.time.Instant to TIMESTAMPTZ on PostgreSQL. V1 used plain
-- TIMESTAMP, which caused schema-validation failures on startup. This migration
-- runs on the already-provisioned Railway database to bring the schema in line.
-- Each column is altered separately: H2 does not support comma-chained ALTER COLUMN.

ALTER TABLE users ALTER COLUMN created_at TYPE TIMESTAMP WITH TIME ZONE;
ALTER TABLE users ALTER COLUMN updated_at TYPE TIMESTAMP WITH TIME ZONE;

ALTER TABLE job_applications ALTER COLUMN created_at                   TYPE TIMESTAMP WITH TIME ZONE;
ALTER TABLE job_applications ALTER COLUMN updated_at                   TYPE TIMESTAMP WITH TIME ZONE;
ALTER TABLE job_applications ALTER COLUMN follow_up_draft_generated_at TYPE TIMESTAMP WITH TIME ZONE;

ALTER TABLE api_keys ALTER COLUMN created_at   TYPE TIMESTAMP WITH TIME ZONE;
ALTER TABLE api_keys ALTER COLUMN last_used_at TYPE TIMESTAMP WITH TIME ZONE;

ALTER TABLE email_verification_tokens ALTER COLUMN expires_at TYPE TIMESTAMP WITH TIME ZONE;
ALTER TABLE email_verification_tokens ALTER COLUMN used_at    TYPE TIMESTAMP WITH TIME ZONE;
ALTER TABLE email_verification_tokens ALTER COLUMN created_at TYPE TIMESTAMP WITH TIME ZONE;

ALTER TABLE user_resumes ALTER COLUMN uploaded_at TYPE TIMESTAMP WITH TIME ZONE;
ALTER TABLE user_resumes ALTER COLUMN analyzed_at TYPE TIMESTAMP WITH TIME ZONE;

ALTER TABLE job_market_snapshots ALTER COLUMN created_at TYPE TIMESTAMP WITH TIME ZONE;

ALTER TABLE skill_demand_snapshots ALTER COLUMN created_at TYPE TIMESTAMP WITH TIME ZONE;

ALTER TABLE dead_letter_events ALTER COLUMN failed_at TYPE TIMESTAMP WITH TIME ZONE;
