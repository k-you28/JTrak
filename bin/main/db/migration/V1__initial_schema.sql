-- V1: Initial schema matching Hibernate-generated tables from entity classes.
-- Supports both H2 (dev/test) and PostgreSQL (production).

-- ── users ────────────────────────────────────────────────────────────────────

CREATE TABLE users (
    id              VARCHAR(36)  NOT NULL PRIMARY KEY,
    email           VARCHAR(320) NOT NULL,
    password_hash   VARCHAR(255) NOT NULL,
    email_verified  BOOLEAN      NOT NULL DEFAULT FALSE,
    status          VARCHAR(255) NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMP    NOT NULL,
    updated_at      TIMESTAMP    NOT NULL,
    CONSTRAINT uq_users_email UNIQUE (email)
);

-- ── job_applications ─────────────────────────────────────────────────────────

CREATE TABLE job_applications (
    id                          VARCHAR(255) NOT NULL PRIMARY KEY,
    request_key                 VARCHAR(255) NOT NULL,
    company_name                VARCHAR(255),
    position_title              VARCHAR(255),
    date_applied                DATE,
    status                      VARCHAR(255),
    notes                       VARCHAR(255),
    source                      VARCHAR(255),
    client_ip                   VARCHAR(255),
    user_id                     VARCHAR(255),
    created_at                  TIMESTAMP,
    updated_at                  TIMESTAMP,
    follow_up_draft             TEXT,
    follow_up_draft_generated_at TIMESTAMP,
    CONSTRAINT uq_job_applications_request_key UNIQUE (request_key)
);

-- Query: findByRequestKeyAndUserId, findByIdAndUserId
CREATE INDEX idx_job_applications_user_id ON job_applications (user_id);

-- Query: findTopByClientIpAndUserIdOrderByCreatedAtDesc
CREATE INDEX idx_job_applications_client_ip_user_id ON job_applications (client_ip, user_id, created_at);

-- ── api_keys ─────────────────────────────────────────────────────────────────

CREATE TABLE api_keys (
    id           VARCHAR(255) NOT NULL PRIMARY KEY,
    key_value    VARCHAR(255) NOT NULL,
    name         VARCHAR(255),
    active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMP,
    last_used_at TIMESTAMP,
    CONSTRAINT uq_api_keys_key_value UNIQUE (key_value)
);

-- ── email_verification_tokens ────────────────────────────────────────────────

CREATE TABLE email_verification_tokens (
    id          VARCHAR(255) NOT NULL PRIMARY KEY,
    user_id     VARCHAR(255) NOT NULL,
    token_hash  VARCHAR(255) NOT NULL,
    expires_at  TIMESTAMP    NOT NULL,
    used_at     TIMESTAMP,
    created_at  TIMESTAMP    NOT NULL,
    CONSTRAINT uq_evt_token_hash UNIQUE (token_hash)
);

CREATE INDEX idx_evt_user_id    ON email_verification_tokens (user_id);
CREATE INDEX idx_evt_expires_at ON email_verification_tokens (expires_at);

-- ── user_resumes ─────────────────────────────────────────────────────────────

CREATE TABLE user_resumes (
    id            VARCHAR(255) NOT NULL PRIMARY KEY,
    user_id       VARCHAR(36)  NOT NULL,
    file_name     VARCHAR(255) NOT NULL,
    pdf_bytes     BYTEA        NOT NULL,
    uploaded_at   TIMESTAMP    NOT NULL,
    analysis_text TEXT,
    analyzed_at   TIMESTAMP,
    CONSTRAINT uq_user_resumes_user_id UNIQUE (user_id)
);

-- ── job_market_snapshots ─────────────────────────────────────────────────────

CREATE TABLE job_market_snapshots (
    id            VARCHAR(255) NOT NULL PRIMARY KEY,
    search_query  VARCHAR(255) NOT NULL,
    page_start    INTEGER      NOT NULL,
    page_end      INTEGER      NOT NULL,
    total_jobs    INTEGER      NOT NULL,
    created_at    TIMESTAMP    NOT NULL,
    error_message VARCHAR(1024)
);

-- Query: findTopByOrderByCreatedAtDesc, findByCreatedAtGreaterThanEqual
CREATE INDEX idx_jms_created_at ON job_market_snapshots (created_at);

-- ── skill_demand_snapshots ───────────────────────────────────────────────────

CREATE TABLE skill_demand_snapshots (
    id               VARCHAR(255) NOT NULL PRIMARY KEY,
    search_query     VARCHAR(255) NOT NULL,
    page             INTEGER      NOT NULL,
    skill_name       VARCHAR(255) NOT NULL,
    occurrence_count INTEGER      NOT NULL,
    sample_jobs      INTEGER      NOT NULL,
    rank_position    INTEGER      NOT NULL,
    created_at       TIMESTAMP    NOT NULL,
    error_message    VARCHAR(1024)
);

-- Query: findTopBySearchQueryAndSkillNameNotOrderByCreatedAtDesc
CREATE INDEX idx_sds_query_created ON skill_demand_snapshots (search_query, created_at);

-- ── dead_letter_events ───────────────────────────────────────────────────────

CREATE TABLE dead_letter_events (
    id             VARCHAR(255) NOT NULL PRIMARY KEY,
    request_key    VARCHAR(255),
    client_ip      VARCHAR(255),
    payload        VARCHAR(2000),
    failure_reason VARCHAR(255),
    failed_at      TIMESTAMP
);
