# Data Model & Database Schema

**Last Updated:** 2026-04-13

**Primary Database:** PostgreSQL (production) / H2 (development)

---

## Overview

The data model is built around **job applications** as the core entity, with supporting tables for user accounts, market intelligence, AI analysis, and failure tracking.

**Key Design Principles:**
- All application data scoped to `userId` (no cross-user leakage)
- Idempotency via unique `requestKey` constraint
- Soft storage of AI analysis (resume PDFs, drafts)
- Immutable snapshots for market intelligence
- Complete failure audit trail (dead letter events)

---

## Entities

### JobApplication

**Table:** `job_applications`

**Purpose:** Core entity — tracks every job application submitted by a user.

**Schema:**

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | UUID | PRIMARY KEY | Auto-generated |
| `request_key` | VARCHAR(255) | UNIQUE, NOT NULL | Idempotency key; auto-generated if omitted |
| `company_name` | VARCHAR(255) | NOT NULL | Normalized company name |
| `position_title` | VARCHAR(255) | NOT NULL | Job title |
| `date_applied` | DATE | NOT NULL | Application date |
| `status` | VARCHAR(20) | | Enum: APPLIED, INTERVIEWING, OFFER, REJECTED |
| `notes` | TEXT | | User notes (max 2000 chars) |
| `source` | VARCHAR(255) | | Where user found the job (LinkedIn, company site, etc.) |
| `client_ip` | VARCHAR(45) | | IPv4 or IPv6 of request origin (for rate limiting) |
| `user_id` | VARCHAR(36) | FK to users(id) | Application owner |
| `created_at` | TIMESTAMP | NOT NULL | Record creation time (ISO 8601 UTC) |
| `updated_at` | TIMESTAMP | NOT NULL | Last modification time |
| `follow_up_draft` | TEXT | | Claude-generated follow-up email (null until generated) |
| `follow_up_draft_generated_at` | TIMESTAMP | | Timestamp of draft generation |

**Indices:**
- `UNIQUE(request_key)` — Prevents duplicate submissions with same key
- `INDEX(user_id)` — Speeds up application listing by user
- `INDEX(client_ip, user_id)` — Supports rate limiting query
- `INDEX(user_id, date_applied, created_at)` — Supports sorted listing

**Relationships:**
- Foreign key: `user_id` → `users(id)` (owner account)

**Example Row:**
```sql
INSERT INTO job_applications (
    id, request_key, company_name, position_title, date_applied, status, notes, source, client_ip, user_id, created_at, updated_at
) VALUES (
    '550e8400-e29b-41d4-a716-446655440000',
    'acme__senior-engineer__2026-04-13',
    'Acme Corp',
    'Senior Software Engineer',
    '2026-04-13',
    'APPLIED',
    'Referral from Jane Smith',
    'LinkedIn',
    '203.0.113.42',
    'user-uuid-1',
    '2026-04-13 10:00:00 UTC',
    '2026-04-13 10:00:00 UTC'
);
```

**Lifecycle:**
1. Created via `POST /api/applications` or web form
2. Status updated via `PATCH /api/applications/{id}/status`
3. Follow-up draft generated on demand (stored in DB)
4. Deleted via `DELETE /api/applications/{id}`

---

### UserAccount

**Table:** `users`

**Purpose:** User registration and authentication. Stores login credentials.

**Schema:**

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | UUID | PRIMARY KEY | Auto-generated |
| `email` | VARCHAR(320) | UNIQUE, NOT NULL | User login email; normalized to lowercase |
| `password_hash` | VARCHAR(255) | NOT NULL | BCrypt hash (adaptive, salted) |
| `status` | VARCHAR(20) | NOT NULL, DEFAULT 'ACTIVE' | Account state (ACTIVE, SUSPENDED) |
| `created_at` | TIMESTAMP | NOT NULL | Registration time |
| `updated_at` | TIMESTAMP | NOT NULL | Last modified (password change, etc.) |

**Indices:**
- `UNIQUE(email)` — One account per email
- `INDEX(status)` — Filter for active accounts

**Relationships:**
- One-to-many: `users.id` ← `job_applications.user_id`
- One-to-one: `users.id` ← `user_resumes.user_id`

**Special Account:**

The **legacy API owner** is a synthetic account created on first API-key request:

| Field | Value |
|-------|-------|
| email | `legacy-api@jobtracker.local` |
| password_hash | Config value (`app.ownership.legacy-password-hash`) |
| status | ACTIVE |

All API-key requests without an authenticated principal are scoped to this account's `id`, enabling backwards compatibility with pre-authentication data.

**Example Rows:**
```sql
-- Regular user
INSERT INTO users (id, email, password_hash, status, created_at, updated_at) VALUES (
    'user-uuid-1',
    'alice@example.com',
    '$2a$10$...',  -- BCrypt hash
    'ACTIVE',
    '2026-03-01 10:00:00 UTC',
    '2026-03-01 10:00:00 UTC'
);

-- Legacy API owner
INSERT INTO users (id, email, password_hash, status, created_at, updated_at) VALUES (
    'legacy-owner-uuid',
    'legacy-api@jobtracker.local',
    '$2a$10$...',  -- From config
    'ACTIVE',
    '2026-01-01 00:00:00 UTC',
    '2026-01-01 00:00:00 UTC'
);
```

---

### ApiKey

**Table:** `api_keys`

**Purpose:** Stores API keys for programmatic access. Enables rate limiting + usage tracking.

**Schema:**

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | UUID | PRIMARY KEY | Auto-generated |
| `key_value` | VARCHAR(255) | UNIQUE, NOT NULL | Format: `jt_<uuid>_<uuid>` |
| `name` | VARCHAR(255) | | User-friendly name (e.g., "Production Integration") |
| `active` | BOOLEAN | DEFAULT true | Soft delete (deactivate without removing) |
| `created_at` | TIMESTAMP | NOT NULL | Key creation time |
| `last_used_at` | TIMESTAMP | | Timestamp of last API request using this key |

**Indices:**
- `UNIQUE(key_value)` — Prevents duplicate keys
- `INDEX(active)` — Filter for active keys only

**Access Control:**
- API keys created via `/admin/api-keys` endpoint (requires authentication)
- No relationship to specific user (all API keys contribute to legacy owner account)
- Revocation: soft delete via `active = false`

**Example Row:**
```sql
INSERT INTO api_keys (id, key_value, name, active, created_at, last_used_at) VALUES (
    'key-uuid-1',
    'jt_abc123def456_xyz789',
    'Production Integration Key',
    true,
    '2026-04-01 10:00:00 UTC',
    '2026-04-13 15:30:00 UTC'
);
```

---

### UserResume

**Table:** `user_resumes`

**Purpose:** Stores uploaded PDF resumes + HR Lens analysis results per user.

**Schema:**

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | UUID | PRIMARY KEY | Auto-generated |
| `user_id` | VARCHAR(36) | UNIQUE, NOT NULL | FK to users(id); one resume per user |
| `file_name` | VARCHAR(255) | NOT NULL | Original filename (e.g., "alice-resume.pdf") |
| `pdf_bytes` | VARBINARY | NOT NULL | Raw PDF file content; defensive copy on get/set |
| `uploaded_at` | TIMESTAMP | NOT NULL | Resume upload time |
| `analysis_text` | TEXT | | JSON string from HR Lens (null until analyzed) |
| `analyzed_at` | TIMESTAMP | | Timestamp of analysis generation |

**Indices:**
- `UNIQUE(user_id)` — One resume per user

**Relationships:**
- Foreign key: `user_id` → `users(id)`

**Important Implementation Details:**

1. **No `@Lob` annotation:** Using plain `byte[]` for VARBINARY compatibility
   - PostgreSQL: VARBINARY maps to BYTEA
   - H2: VARBINARY (native type)
   - Avoids PostgreSQL Large Object (OID) which is incompatible with H2

2. **Defensive Copies:** PDF bytes are cloned on get/set to prevent mutation
   ```java
   public byte[] getPdfBytes() {
       return pdfBytes == null ? null : pdfBytes.clone();
   }
   public void setPdfBytes(byte[] pdfBytes) {
       this.pdfBytes = pdfBytes == null ? null : pdfBytes.clone();
   }
   ```

**Example Row:**
```sql
INSERT INTO user_resumes (
    id, user_id, file_name, pdf_bytes, uploaded_at, analysis_text, analyzed_at
) VALUES (
    'resume-uuid-1',
    'user-uuid-1',
    'alice-resume-2026.pdf',
    E'\\x255044462d312e34...',  -- PDF binary data (hex in SQL)
    '2026-04-10 14:00:00 UTC',
    '{"pros": [...], "cons": [...], ...}',
    '2026-04-10 14:05:00 UTC'
);
```

---

### JobMarketSnapshot

**Table:** `job_market_snapshots`

**Purpose:** Immutable snapshots of job market size (via Adzuna API). Used for trend analysis.

**Schema:**

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | UUID | PRIMARY KEY | Auto-generated |
| `search_query` | VARCHAR(255) | NOT NULL | Job search query (e.g., "software") |
| `page_start` | INT | NOT NULL | First page fetched |
| `page_end` | INT | NOT NULL | Last page (calculated from total jobs) |
| `total_jobs` | INT | NOT NULL | Estimated total job postings |
| `created_at` | TIMESTAMP | NOT NULL | Snapshot creation time |
| `error_message` | VARCHAR(1024) | | Non-null if fetch failed |

**Indices:**
- `INDEX(created_at DESC)` — Supports trend queries (latest first)
- `INDEX(search_query, created_at)` — Supports per-query trend history

**Polling Strategy:**
1. On startup (`ApplicationReadyEvent`), fetch immediately if enabled
2. Every 15 minutes (configurable `app.market.poll-interval-ms`), fetch again
3. Each snapshot is immutable; new fetches create new rows
4. Trend analysis queries last 30 days and compresses to daily (one row per day)

**Example Rows:**
```sql
-- Successful fetch
INSERT INTO job_market_snapshots (id, search_query, page_start, page_end, total_jobs, created_at, error_message) VALUES (
    'snap-uuid-1',
    'software',
    1, 50,
    50000,
    '2026-04-13 10:00:00 UTC',
    NULL
);

-- Failed fetch
INSERT INTO job_market_snapshots (id, search_query, page_start, page_end, total_jobs, created_at, error_message) VALUES (
    'snap-uuid-2',
    'software',
    1, 1,
    0,
    '2026-04-13 10:15:00 UTC',
    'HTTP 429 Too Many Requests'
);
```

---

### SkillDemandSnapshot

**Table:** `skill_demand_snapshots`

**Purpose:** Top in-demand skills extracted from job postings, ranked by frequency.

**Schema:**

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | UUID | PRIMARY KEY | Auto-generated |
| `search_query` | VARCHAR(255) | NOT NULL | Job role (e.g., "software engineer") |
| `page` | INT | NOT NULL | Which page of Adzuna results |
| `skill_name` | VARCHAR(255) | NOT NULL | Skill (e.g., "Python") |
| `occurrence_count` | INT | NOT NULL | Times found in job postings |
| `sample_jobs` | INT | NOT NULL | Number of postings analyzed |
| `rank_position` | INT | NOT NULL | Rank within top N (1-8) |
| `created_at` | TIMESTAMP | NOT NULL | Snapshot creation time |
| `error_message` | VARCHAR(1024) | | Non-null if extraction failed |

**Indices:**
- `INDEX(search_query, created_at DESC)` — Latest skills per role
- `INDEX(skill_name, search_query)` — Skill trend history

**Dedupe Strategy (Fuzzy Matching):**

When counting skills, aliases are normalized:

```
"Power BI" ← ["powerbi", "power-bi", "power bi"]
"Spark" ← ["pyspark", "apache spark", "spark"]
"Airflow" ← ["apache airflow", "airflow"]
```

Final row has canonical skill name (e.g., "Power BI") with aggregated count.

**Example Rows:**
```sql
-- Latest top 8 skills for Software Engineer (from latest fetch)
INSERT INTO skill_demand_snapshots (
    id, search_query, page, skill_name, occurrence_count, sample_jobs, rank_position, created_at
) VALUES
    ('snap-uuid-1', 'software engineer', 1, 'Python', 87, 100, 1, '2026-04-13 10:00:00 UTC'),
    ('snap-uuid-2', 'software engineer', 1, 'Go', 72, 100, 2, '2026-04-13 10:00:00 UTC'),
    ('snap-uuid-3', 'software engineer', 1, 'Kubernetes', 68, 100, 3, '2026-04-13 10:00:00 UTC'),
    ...;

-- Data Engineer role skills
INSERT INTO skill_demand_snapshots (
    id, search_query, page, skill_name, occurrence_count, sample_jobs, rank_position, created_at
) VALUES
    ('snap-uuid-9', 'data engineer', 1, 'SQL', 95, 100, 1, '2026-04-13 10:00:00 UTC'),
    ('snap-uuid-10', 'data engineer', 1, 'Python', 91, 100, 2, '2026-04-13 10:00:00 UTC'),
    ...;
```

---

### DeadLetterEvent

**Table:** `dead_letter_events`

**Purpose:** Audit trail of failed submissions. Useful for debugging + recovery.

**Schema:**

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | UUID | PRIMARY KEY | Auto-generated |
| `request_key` | VARCHAR(255) | | Idempotency key (may be null pre-resolution) |
| `client_ip` | VARCHAR(45) | | Request origin (IPv4 or IPv6) |
| `payload` | VARCHAR(2000) | | Request data (company, position, date) |
| `failure_reason` | VARCHAR(255) | | Exception class + message (e.g., "ValidationError: Company name required") |
| `failed_at` | TIMESTAMP | | Failure timestamp |

**Indices:**
- `INDEX(request_key)` — Find retries for same request
- `INDEX(client_ip)` — Investigate abuse from IP
- `INDEX(failed_at DESC)` — Recent failures first

**Recording:**

Triggered by any exception in `JobApplicationService.submit()`:

```java
catch (Exception e) {
    metrics.recordDeadLetter();
    deadLetterService.record(new DeadLetterEvent(
        key, clientIp,
        String.format("company=%s, position=%s, date=%s", ...),
        e.getClass().getSimpleName() + ": " + e.getMessage()
    ));
    throw e;  // Re-throw for 500 response
}
```

**Example Rows:**
```sql
-- Validation error
INSERT INTO dead_letter_events (
    id, request_key, client_ip, payload, failure_reason, failed_at
) VALUES (
    'dle-uuid-1',
    'acme__engineer__2026-04-13',
    '203.0.113.42',
    'company=, position=Senior Engineer, date=2026-04-13',
    'IllegalArgumentException: Company name required',
    '2026-04-13 10:00:00 UTC'
);

-- Rate limit
INSERT INTO dead_letter_events (
    id, request_key, client_ip, payload, failure_reason, failed_at
) VALUES (
    'dle-uuid-2',
    'acme__engineer__2026-04-13',
    '203.0.113.42',
    'company=Acme Corp, position=Senior Engineer, date=2026-04-13',
    'IllegalStateException: Rate limit exceeded - attempted overwrite too soon',
    '2026-04-13 10:00:02 UTC'
);

-- API error (Claude timeout)
INSERT INTO dead_letter_events (
    id, request_key, client_ip, payload, failure_reason, failed_at
) VALUES (
    'dle-uuid-3',
    '',
    '203.0.113.42',
    'company=Acme Corp, position=Senior Engineer, date=2026-04-13',
    'IllegalArgumentException: AI parsing failed: Read timed out',
    '2026-04-13 10:05:00 UTC'
);
```

**Usage:**
- Manual inspection via SQL: `SELECT * FROM dead_letter_events ORDER BY failed_at DESC LIMIT 20`
- Identify patterns (frequent validation errors, API timeouts, abuse IPs)
- Potential future: Admin UI dashboard for dead letter review + retry

---

## Data Flow Diagrams

### Application Submission (Idempotency)

```
Client (API Key)
    │
    └─→ POST /api/applications
        │
        ├─ Extract clientIp (from request headers)
        ├─ Resolve ownerUserId (from legacy owner)
        ├─ Generate requestKey (if omitted)
        │
        ├─ Query: findByRequestKeyAndUserId(key, userId)
        │   ├─ [NOT FOUND]
        │   │   ├─ Check per-IP rate limit (2s window)
        │   │   ├─ [ALLOWED] → Create new JobApplication
        │   │   └─ [LIMITED] → throw 429, record DeadLetterEvent
        │   │
        │   └─ [FOUND]
        │       ├─ Compare content (company, position, date, status)
        │       ├─ [SAME] → Return existing (replay), metrics.recordReplayed()
        │       │
        │       └─ [DIFFERENT]
        │           ├─ Check 2s window from creation
        │           ├─ [WITHIN WINDOW] → throw 429, record DeadLetterEvent
        │           └─ [AFTER WINDOW] → Update existing, metrics.recordCreated()
        │
        └─ [ANY ERROR] → record DeadLetterEvent, throw 500
```

### Resume Analysis (HR Lens)

```
User (Web UI, logged in)
    │
    └─ POST /api/hr-lens/upload [resume.pdf]
        │
        ├─ Validate PDF
        ├─ Extract text (PDFBox)
        │
        ├─ Fetch data:
        │   ├─ Job applications (last 25)
        │   ├─ Market skills (Software Engineer role)
        │
        ├─ Build prompt:
        │   ├─ Resume text
        │   ├─ Application stats (interviews, offers, rejections)
        │   ├─ Top 8 in-demand skills
        │
        ├─ Call Claude Haiku
        │
        ├─ Parse JSON (with fallback)
        │
        └─ Store/replace UserResume (upsert)
            └─ Save analysis_text + analyzed_at
```

### Market Intelligence Polling

```
On startup (ApplicationReadyEvent) + every 15 minutes:
    │
    ├─ Call Adzuna API (/v1/api/jobs/us/search/1)
    │   ├─ Query: app.market.query (e.g., "software")
    │   └─ Response: { count: 50000, ... }
    │
    ├─ Calculate total jobs + last page
    │
    ├─ Create JobMarketSnapshot
    │   ├─ search_query = "software"
    │   ├─ page_start = 1
    │   ├─ page_end = 50 (calculated)
    │   ├─ total_jobs = 50000
    │   └─ error_message = null (or error text if failed)
    │
    └─ Save to DB (immutable row)
```

### Skill Demand Extraction

```
On startup (ApplicationReadyEvent) + every 15 minutes:
    │
    ├─ For each role (Software Engineer, Data Engineer, AI Engineer):
    │   │
    │   ├─ Fetch pages 1-5 from Adzuna API (20 results each = 100 postings)
    │   │
    │   ├─ Extract HTML job descriptions
    │   │
    │   ├─ Search for skill keywords (Python, Go, Kubernetes, ...)
    │   │
    │   ├─ Count occurrences + apply dedupe (fuzzy aliases)
    │   │
    │   ├─ Sort by count (descending)
    │   │
    │   └─ For each top 8 skill:
    │       └─ Create SkillDemandSnapshot
    │           ├─ search_query = "software engineer"
    │           ├─ skill_name = "Python"
    │           ├─ occurrence_count = 87
    │           ├─ rank_position = 1
    │           └─ sample_jobs = 100
    │
    └─ Save all snapshots (immutable rows)
```

---

## Constraints & Validation

### At Database Level

| Entity | Constraint | Enforced | Notes |
|--------|-----------|----------|-------|
| JobApplication | UNIQUE(request_key) | DB | Prevents duplicate submissions |
| JobApplication | NOT NULL(user_id) | DB | All apps must have owner |
| UserAccount | UNIQUE(email) | DB | One account per email |
| UserAccount | NOT NULL(password_hash) | DB | All users must authenticate |
| ApiKey | UNIQUE(key_value) | DB | Keys cannot be duplicated |
| UserResume | UNIQUE(user_id) | DB | One resume per user (upsert on replace) |

### At Application Level

| Endpoint | Validation | Notes |
|----------|-----------|-------|
| POST /api/applications | companyName != null && !blank | Custom message |
| POST /api/applications | positionTitle != null && !blank | Custom message |
| POST /api/applications | dateApplied != null | Custom message |
| PATCH /api/applications/{id}/status | status in [APPLIED, INTERVIEWING, OFFER, REJECTED] | Case-insensitive, normalized to UPPERCASE |
| POST /api/resume/parse | file is PDF or DOCX | Check extension + content-type |
| POST /api/hr-lens/upload | file is PDF only | Check extension + content-type |

---

## Migration & Backwards Compatibility

### Legacy Owner Backfill

**Purpose:** Migrate pre-authentication data (owned by no one) to legacy account.

**Service:** `JobApplicationOwnershipBackfill`

**Trigger:** Manual execution (not automatic on startup)

**Operation:**
```java
@Transactional
public void backfillOwnership() {
    // Find all JobApplications with user_id = NULL
    List<JobApplication> orphaned = findApplicationsWithNullUserId();

    // Assign to legacy owner
    UserAccount legacy = getOrCreateLegacyOwner();

    for (JobApplication app : orphaned) {
        app.setUserId(legacy.getId());
        applicationRepository.save(app);
    }
}
```

**Usage:**
```bash
./gradlew bootRun
# Then call endpoint or service method manually
```

---

## Query Examples

### Find user's applications sorted by date

```sql
SELECT * FROM job_applications
WHERE user_id = 'user-uuid-1'
ORDER BY date_applied DESC, created_at DESC;
```

### Find stale applications (2+ weeks without status change)

```sql
SELECT * FROM job_applications
WHERE user_id = 'user-uuid-1'
  AND updated_at < (NOW() - INTERVAL '14 days')
  AND status = 'APPLIED'
ORDER BY updated_at ASC;
```

### Market trend (last 30 days)

```sql
SELECT created_at, total_jobs FROM job_market_snapshots
WHERE created_at >= (NOW() - INTERVAL '30 days')
  AND search_query = 'software'
ORDER BY created_at ASC;
```

### Top 8 in-demand skills (latest fetch)

```sql
SELECT skill_name, occurrence_count, rank_position
FROM skill_demand_snapshots
WHERE search_query = 'software engineer'
  AND created_at = (
    SELECT MAX(created_at) FROM skill_demand_snapshots
    WHERE search_query = 'software engineer'
  )
ORDER BY rank_position ASC
LIMIT 8;
```

### Recent failures (debugging)

```sql
SELECT * FROM dead_letter_events
ORDER BY failed_at DESC
LIMIT 20;
```

### API key usage (last used)

```sql
SELECT key_value, name, last_used_at, active
FROM api_keys
WHERE active = true
ORDER BY last_used_at DESC NULLS LAST;
```

---

## Performance Considerations

1. **Indices:** All frequently queried columns indexed (user_id, request_key, created_at, email)
2. **Partitioning:** Not needed (typical user: 10-100 applications)
3. **Archive Strategy:** DeadLetterEvent may grow over time; consider archiving old rows quarterly
4. **Snapshot Growth:** JobMarketSnapshot + SkillDemandSnapshot grow ~50 rows per polling cycle; acceptable at typical volumes
5. **PDF Storage:** UserResume.pdf_bytes stored in BYTEA; PostgreSQL handles efficiently for typical ~1-5MB PDFs

---

## Testing & Local Development

### H2 (In-Memory, Development)

```yaml
spring:
  datasource:
    url: jdbc:h2:mem:testdb
    driver-class-name: org.h2.Driver
  jpa:
    database-platform: org.hibernate.dialect.H2Dialect
```

**Schema auto-creation:** DDL scripts in `src/main/resources/schema.sql`

### PostgreSQL (Production)

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/jobtracker
    username: postgres
    password: ...
  jpa:
    hibernate.ddl-auto: validate
    database-platform: org.hibernate.dialect.PostgreSQLDialect
```

**Schema management:** Use `flyway` or manual migrations.

---

## Security & Data Privacy

1. **Encryption at Rest:** PostgreSQL optional (`pgcrypto` extension for sensitive columns)
2. **Encryption in Transit:** HTTPS enforced via HSTS headers
3. **Data Isolation:** All queries scoped to userId; verified by tests
4. **Password Hashing:** BCrypt with adaptive cost factor
5. **Audit Trail:** DeadLetterEvent captures failures; recommend audit table for account changes
6. **Retention Policy:** No automatic purge; recommend deleting old DeadLetterEvent rows quarterly
