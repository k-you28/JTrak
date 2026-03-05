# ADR 0001: User Accounts, Authentication, and Email Verification

- Status: Accepted
- Date: 2026-03-03
- Owners: Job Tracker maintainers

## Context

The current application supports:
- Web UI (Thymeleaf)
- API key authentication for `/api/applications`
- A single shared data set of job applications

The next major requirement is multi-user support:
- Each user must only access their own application records.
- Users must authenticate with email + password.
- Emails must be verified before full account access.
- Existing records must be safely migrated without data loss.

This is a high-risk change because it affects security boundaries, schema, and core read/write queries.

## Decision Summary

We will implement account support using:
- Session-based authentication for web UI (`Spring Security` form login).
- Database-backed user accounts with email as primary identity.
- Mandatory email verification before interactive login is allowed.
- Ownership scoping on all job application data via `user_id`.
- Token tables for verification and password reset with hashed tokens.
- Incremental rollout in small, deployable slices (not a single large merge).

API key auth remains for machine-to-machine API usage, and keys will eventually be associated with account ownership.

## Detailed Decisions

### 1) Authentication model

Decision:
- Use server-side session auth for web UI.

Why:
- The app already renders pages server-side.
- Session auth is simpler and safer for this architecture than introducing JWT complexity.

Non-goal:
- This ADR does not move the app to SPA/mobile auth patterns.

### 2) Identity and account model

Decision:
- `email` is the login identifier and must be unique (case-insensitive normalization to lowercase).
- Passwords are stored only as strong one-way hashes (BCrypt or Argon2).
- Add account status fields for future lock/disable behaviors.

### 3) Verification model

Decision:
- Registration creates an unverified account.
- Verification email contains a random one-time token.
- Only token hashes are stored in DB.
- Token has expiry and single-use semantics.
- Login is blocked until `email_verified = true`.

### 4) Data ownership model

Decision:
- Every `job_application` row has exactly one owner via `user_id`.
- All repository/service access must be scoped by current authenticated user.
- Incoming client payload must never control ownership assignment.

Security rule:
- Cross-user access must fail as not found or forbidden.

## Schema Changes

The following migrations are required.

### New table: `users`

Columns:
- `id` BIGINT PK
- `email` VARCHAR(320) NOT NULL UNIQUE
- `password_hash` VARCHAR(...) NOT NULL
- `email_verified` BOOLEAN NOT NULL DEFAULT FALSE
- `status` VARCHAR(32) NOT NULL DEFAULT 'ACTIVE'
- `created_at` TIMESTAMP NOT NULL
- `updated_at` TIMESTAMP NOT NULL
- `last_login_at` TIMESTAMP NULL

Indexes:
- unique index on normalized `email`

### New table: `email_verification_tokens`

Columns:
- `id` BIGINT PK
- `user_id` BIGINT NOT NULL FK -> users(id)
- `token_hash` VARCHAR(...) NOT NULL
- `expires_at` TIMESTAMP NOT NULL
- `used_at` TIMESTAMP NULL
- `created_at` TIMESTAMP NOT NULL

Indexes:
- index on `user_id`
- index on `expires_at`
- unique index on `token_hash`

### New table: `password_reset_tokens`

Columns:
- `id` BIGINT PK
- `user_id` BIGINT NOT NULL FK -> users(id)
- `token_hash` VARCHAR(...) NOT NULL
- `expires_at` TIMESTAMP NOT NULL
- `used_at` TIMESTAMP NULL
- `created_at` TIMESTAMP NOT NULL

Indexes:
- index on `user_id`
- index on `expires_at`
- unique index on `token_hash`

### Change table: `job_application`

Columns:
- Add `user_id` BIGINT FK -> users(id)

Migration sequence:
1. Add nullable `user_id`.
2. Create a bootstrap owner user.
3. Backfill all existing records to bootstrap user.
4. Add NOT NULL constraint to `user_id`.
5. Add index on `user_id` and on common query filters including `user_id`.

## Request Lifecycle Changes

### Registration
1. Validate email and password policy.
2. Normalize email to lowercase.
3. Store hashed password.
4. Create unverified user.
5. Generate verification token and send email.

### Email verification
1. User clicks verification link with raw token.
2. System hashes token and loads matching unexpired unused token.
3. Mark user verified.
4. Mark token used.

### Login
1. Authenticate email/password.
2. Reject login if email not verified.
3. Start session on success.

### Job application CRUD
1. Resolve current authenticated user.
2. Apply `user_id` scoping in every read/update/delete query.
3. Set `user_id` server-side during create.

## API and Security Compatibility

- Existing API-key paths remain available.
- Ownership still applies: API actions must be tied to account context (phase 2 hardening).
- CSRF stays enabled for browser form endpoints.
- Rate limiting will be added to registration/login/reset flows.

## Rollout Plan (Mandatory)

Implement in vertical slices:

### Slice 1: Accounts + login baseline
- Add `users` table and auth wiring.
- Add register/login/logout.
- Add tests for basic auth flow.

### Slice 2: Email verification
- Add verification token table and email flow.
- Enforce verified-only login.
- Add resend verification endpoint with cooldown.

### Slice 3: Ownership migration
- Add `user_id` to job applications with safe backfill.
- Scope all queries by owner.
- Add cross-user access tests.

### Slice 4: Password reset
- Add reset token table and flows.
- Add expiry/reuse tests.

### Slice 5: Hardening
- Rate limit auth endpoints.
- Add audit events for auth operations.
- Associate API keys to account owner.

## Testing Requirements

No slice is complete without these:

- Unit tests:
  - Password hashing + verify
  - Token generation, expiry, one-time use
- Integration tests:
  - Register -> verify -> login
  - Login blocked while unverified
  - User A cannot read/write User B records
  - Password reset success/failure paths
- Regression tests:
  - Existing API-key behavior still valid where intended

## Operational Requirements

- Use migration tooling (Flyway/Liquibase), no manual schema edits.
- Log security events without logging secrets/tokens/passwords.
- Track metrics for registration, verification, login failures, token failures.
- Provide email sender abstraction so local/dev can run without real SMTP.

## Alternatives Considered

### Alternative A: JWT everywhere

Rejected for now because:
- Adds token lifecycle and refresh complexity not needed for current Thymeleaf app.
- Session auth is faster to ship safely in this architecture.

### Alternative B: Add auth, defer ownership scoping

Rejected because:
- Creates a dangerous period where authenticated users can still see shared data.
- Violates the core requirement for per-account isolation.

### Alternative C: No email verification

Rejected because:
- Weakens account integrity and password reset trust model.

## Risks and Mitigations

Risk:
- Breaking existing data access during migration.
Mitigation:
- Use phased nullable -> backfill -> non-null migration and integration tests.

Risk:
- Security regressions from missing user scoping in one code path.
Mitigation:
- Require repository methods to include `user_id`; add dedicated access-control tests.

Risk:
- Email delivery failures block activation.
Mitigation:
- Resend endpoint, clear error UX, and operational alerts.

## Consequences

Positive:
- Proper per-user isolation.
- Stronger security posture for production.
- Foundation for account-level analytics and notifications.

Negative:
- More schema and service complexity.
- More test surface and rollout coordination.
- Additional operational dependency (email sending).

## Implementation Checklist

- [ ] Add migration framework if missing
- [ ] Create users + token tables
- [ ] Add Spring Security session auth
- [ ] Build register/login/logout
- [ ] Build email verify + resend
- [ ] Add `user_id` to applications and backfill
- [ ] Scope all application queries by current user
- [ ] Add password reset flow
- [ ] Add auth security tests and ownership tests
- [ ] Update README with auth setup and local email testing
