# REST API Reference

**Last Updated:** 2026-04-13

## Overview

The Job Application Tracker exposes a REST API for programmatic submission and management of job applications. All endpoints support idempotency through request keys, rate limiting, and comprehensive error tracking.

### Authentication

Two authentication methods are supported:

1. **Web UI (Form Login)**
   - Users log in with email and password at `/login`
   - Email verification required for new accounts before first login
   - Session cookies manage authentication

2. **REST API (API Key)**
   - Clients send `X-API-Key: jt_<key>` header with each request
   - Alternative: `Authorization: ApiKey jt_<key>` header
   - Key validation by `ApiKeyAuthenticationFilter`
   - Admin endpoints (`/admin/api-keys`) require form-based authentication

**Legacy behavior:** API requests without a user context (no authenticated session or user principal) are pinned to the synthetic account `legacy-api@jobtracker.local` for backwards compatibility with pre-authentication data.

---

## Endpoints

### Applications

#### Submit Application (Idempotent)

```http
POST /api/applications
X-API-Key: jt_<key>
Content-Type: application/json

{
  "companyName": "Acme Corp",
  "positionTitle": "Senior Software Engineer",
  "dateApplied": "2026-04-13",
  "requestKey": "company-slug__position-slug__date-applied",
  "status": "APPLIED",
  "notes": "Referral from Jane Smith",
  "source": "LinkedIn"
}
```

**Response (201 Created):**
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "requestKey": "company-slug__position-slug__date-applied",
  "companyName": "Acme Corp",
  "positionTitle": "Senior Software Engineer",
  "dateApplied": "2026-04-13",
  "status": "APPLIED",
  "notes": "Referral from Jane Smith",
  "source": "LinkedIn",
  "clientIp": "203.0.113.42",
  "userId": "user-uuid-here",
  "createdAt": "2026-04-13T10:00:00Z",
  "updatedAt": "2026-04-13T10:00:00Z",
  "followUpDraft": null,
  "followUpDraftGeneratedAt": null
}
```

**Request Key (Idempotency):**

- If omitted, auto-generated as: `{company-slug}__{position-slug}__{date-applied}`
- Same key + same content (within 2 seconds) → returns 200 with existing record (replay)
- Same key + different content within 2 seconds → returns 429 rate limit
- Same key + different content after 2 seconds → upserts record (allowed)

**Rate Limiting:**

- Two back-to-back submissions from the same IP within 2 seconds → 429 (rate limited)
- Failed submission → recorded in DeadLetterEvent with failure reason

**Error Responses:**

| Status | Reason |
|--------|--------|
| 400 | Missing/blank company name, position title, or date applied |
| 429 | Rate limit exceeded (too many requests from same IP within 2s) |
| 401 | Invalid/missing API key |
| 500 | Server error; failure recorded in dead letter queue |

---

#### List Applications

```http
GET /api/applications
X-API-Key: jt_<key>
```

**Response (200 OK):**
```json
[
  {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "requestKey": "company-slug__position-slug__date-applied",
    "companyName": "Acme Corp",
    "positionTitle": "Senior Software Engineer",
    "dateApplied": "2026-04-13",
    "status": "APPLIED",
    "notes": "Referral from Jane Smith",
    "source": "LinkedIn",
    "clientIp": "203.0.113.42",
    "userId": "user-uuid-here",
    "createdAt": "2026-04-13T10:00:00Z",
    "updatedAt": "2026-04-13T10:00:00Z",
    "followUpDraft": null,
    "followUpDraftGeneratedAt": null
  }
]
```

Sorted by `dateApplied` (descending), then `createdAt` (descending).

---

#### Get Application by Request Key

```http
GET /api/applications/{requestKey}
X-API-Key: jt_<key>
```

**Response (200 OK):**
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "requestKey": "company-slug__position-slug__date-applied",
  "companyName": "Acme Corp",
  "positionTitle": "Senior Software Engineer",
  "dateApplied": "2026-04-13",
  "status": "APPLIED",
  "notes": "Referral from Jane Smith",
  "source": "LinkedIn",
  "clientIp": "203.0.113.42",
  "userId": "user-uuid-here",
  "createdAt": "2026-04-13T10:00:00Z",
  "updatedAt": "2026-04-13T10:00:00Z",
  "followUpDraft": null,
  "followUpDraftGeneratedAt": null
}
```

**Error Responses:**

| Status | Reason |
|--------|--------|
| 404 | Application not found |
| 401 | Unauthorized |

---

#### Update Application Status

```http
PATCH /api/applications/{id}/status
X-API-Key: jt_<key>
Content-Type: application/json

{
  "status": "INTERVIEWING"
}
```

**Valid statuses:** `APPLIED`, `INTERVIEWING`, `OFFER`, `REJECTED`

**Response (204 No Content)**

**Error Responses:**

| Status | Reason |
|--------|--------|
| 400 | Invalid status value or missing id |
| 404 | Application not found |
| 401 | Unauthorized |

---

#### Delete Application

```http
DELETE /api/applications/{id}
X-API-Key: jt_<key>
```

**Response (204 No Content)**

**Error Responses:**

| Status | Reason |
|--------|--------|
| 404 | Application not found |
| 401 | Unauthorized |

---

### Resume Parsing

#### Parse Resume (AI-Powered)

Extracts structured data from a resume (PDF or DOCX) using Claude Haiku for pre-filling the application form.

```http
POST /api/resume/parse
Content-Type: multipart/form-data

[file: resume.pdf]
```

**Response (200 OK):**
```json
{
  "currentTitle": "Senior Software Engineer",
  "targetTitle": "Staff Engineer",
  "summary": "15+ years building scalable systems at startups and tech companies. Expertise in distributed systems, cloud infrastructure, and mentoring teams.",
  "topSkills": ["Go", "Kubernetes", "AWS", "Rust"],
  "notes": "Current role: Senior Software Engineer. Key skills: Go, Kubernetes, AWS, Rust. Founder of open-source project with 5k+ GitHub stars."
}
```

**Features:**
- Accepts PDF or DOCX files only
- Extracts text up to 8,000 characters to control token usage
- Returns structured JSON for immediate form pre-fill
- Requires `ANTHROPIC_API_KEY` environment variable

**Error Responses:**

| Status | Reason |
|--------|--------|
| 400 | Unsupported file type, empty file, unreadable PDF/DOCX, or ANTHROPIC_API_KEY not configured |
| 500 | Claude API call failed |

---

### HR Lens (Resume Analysis)

#### Upload Resume for HR Lens Analysis

Stores a PDF resume, extracts text, analyzes with Claude Haiku against application history and live market skill demand data, returns structured feedback.

```http
POST /api/hr-lens/upload
Content-Type: multipart/form-data
Authorization: Bearer <session-token>

[file: resume.pdf]
```

**Response (200 OK):**
```json
{
  "pros": [
    "Strong track record at FAANG-tier companies",
    "Multiple open-source projects with 5k+ stars",
    "Consistent interviewing success (40% offer rate)",
    "Deep expertise in Go and Kubernetes"
  ],
  "cons": [
    "No explicit cloud certification despite 15 years experience",
    "Gaps in Rust experience—critical for your target tier",
    "Resume doesn't quantify scale (users, QPS, team size)",
    "No recent speaking engagements or media presence"
  ],
  "improvements": [
    {
      "title": "Add quantified impact metrics",
      "description": "Rewrite 3 bullets using XYZ formula: 'Led redesign of payment system (X=12M users), improved latency (Y=from 500ms to 100ms), eliminated 2k lines of code (Z=cost savings)'"
    },
    {
      "title": "Build public Rust project",
      "description": "Contribute to or start a popular Rust project targeting systems programming. Aim for 200+ stars in 4 months to demonstrate hands-on expertise"
    },
    {
      "title": "Target Series A startups instead of Big Tech",
      "description": "Your 40% interview-to-offer rate on FAANG roles suggests overqualification or poor message-market fit. Shift 50% of applications to well-funded startups (Seed+) where your scale background is rare"
    },
    {
      "title": "Earn AWS Solutions Architect cert",
      "description": "You have the experience; a cert costs $150 and closes a perceived gap for cloud-heavy recruiters"
    },
    {
      "title": "Start a monthly engineering blog",
      "description": "Write 2-3 technical posts per month. Recruiters at top-tier companies view published writing as a signal of thought leadership and communication clarity"
    }
  ],
  "conclusion": "Your background is genuinely strong, but your resume underrepresents impact and the market signal is confused. The 40% offer rate suggests you're targeting roles slightly above your current market fit. Sharpen quantified wins, build a Rust portfolio piece, and shift target company size downward. You'll see offer rate jump to 60%+ within 2 months."
}
```

**Stored Per-User:**
- Replaces any previous resume for the account
- Uses authenticated user context (requires login)
- Includes PDF bytes, analysis JSON, and timestamps

**Error Responses:**

| Status | Reason |
|--------|--------|
| 400 | Non-PDF file, empty file, unreadable PDF, or ANTHROPIC_API_KEY not configured |
| 401 | Not authenticated |
| 500 | Claude API call failed |

---

### Metrics

#### Get Application Metrics

```http
GET /api/metrics
```

**Response (200 OK):**
```json
{
  "created": 42,
  "replayed": 8,
  "rateLimited": 2,
  "deadLettered": 1,
  "appRuntimeSeconds": 3600
}
```

**Metrics:**
- `created` — New applications successfully recorded
- `replayed` — Idempotent replays (same key + same content)
- `rateLimited` — Submissions rejected due to rate limiting
- `deadLettered` — Failed submissions recorded in dead letter queue
- `appRuntimeSeconds` — Seconds since application started

**Auth:** None required (public endpoint)

---

### Admin

#### Create API Key

```http
POST /admin/api-keys
Content-Type: application/json
[Authenticated user required]

{
  "name": "Production Integration Key"
}
```

**Response (201 Created):**
```json
{
  "id": "key-uuid-here",
  "keyValue": "jt_abc123def456...",
  "name": "Production Integration Key",
  "active": true,
  "createdAt": "2026-04-13T10:00:00Z",
  "lastUsedAt": null
}
```

**Important:** Return the full `keyValue` to the user immediately; it is not retrievable later. Store securely (e.g., environment variable, secrets vault).

**Error Responses:**

| Status | Reason |
|--------|--------|
| 401 | Not authenticated |

---

#### Deactivate API Key

```http
DELETE /admin/api-keys/{id}
[Authenticated user required]
```

**Response (204 No Content)**

**Error Responses:**

| Status | Reason |
|--------|--------|
| 401 | Not authenticated |

---

## Error Handling

All error responses follow a consistent format:

```json
{
  "error": "BadRequest",
  "message": "Company name is required"
}
```

Common error scenarios:

| Scenario | HTTP Status | Message |
|----------|------------|---------|
| Missing required field | 400 | Field name is required |
| Invalid status enum | 400 | Invalid status: [value] |
| Rate limited | 429 | Rate limit exceeded |
| Unauthorized (invalid API key) | 401 | Valid API key required. Use X-API-Key or Authorization: ApiKey <key> |
| Record not found | 404 | Application not found |
| Database error | 500 | Internal server error |

---

## Client Examples

### Python with Requests

```python
import requests

BASE_URL = "http://localhost:8080/api"
API_KEY = "jt_your_key_here"

headers = {"X-API-Key": API_KEY}

# Submit application (idempotent)
response = requests.post(
    f"{BASE_URL}/applications",
    json={
        "companyName": "Acme Corp",
        "positionTitle": "Senior Engineer",
        "dateApplied": "2026-04-13",
        "status": "APPLIED",
        "notes": "Referral",
        "source": "LinkedIn"
    },
    headers=headers
)
print(response.status_code, response.json())

# List all applications
response = requests.get(f"{BASE_URL}/applications", headers=headers)
print(response.json())

# Update status
response = requests.patch(
    f"{BASE_URL}/applications/{app_id}/status",
    json={"status": "INTERVIEWING"},
    headers=headers
)
print(response.status_code)
```

### cURL

```bash
API_KEY="jt_your_key_here"

# Submit application
curl -X POST http://localhost:8080/api/applications \
  -H "X-API-Key: $API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "companyName": "Acme Corp",
    "positionTitle": "Senior Engineer",
    "dateApplied": "2026-04-13",
    "status": "APPLIED",
    "notes": "Referral",
    "source": "LinkedIn"
  }'

# List applications
curl -X GET http://localhost:8080/api/applications \
  -H "X-API-Key: $API_KEY"

# Get metrics
curl -X GET http://localhost:8080/api/metrics
```

### JavaScript/Node.js with Fetch

```javascript
const API_KEY = "jt_your_key_here";

async function submitApplication() {
  const response = await fetch("/api/applications", {
    method: "POST",
    headers: {
      "X-API-Key": API_KEY,
      "Content-Type": "application/json"
    },
    body: JSON.stringify({
      companyName: "Acme Corp",
      positionTitle: "Senior Engineer",
      dateApplied: "2026-04-13",
      status: "APPLIED",
      notes: "Referral",
      source: "LinkedIn"
    })
  });

  return await response.json();
}
```

---

## Rate Limiting Policy

**Per-IP rate limiting:** 2-second window
- Two submissions from the same IP within 2 seconds → 429 rate limit on second request
- Window resets after 2 seconds of inactivity
- Idempotent replays (same content) always succeed within window

**Per-request-key rate limiting:** Content change detection
- Same `requestKey` with different content within 2 seconds → 429 rate limit
- Same `requestKey` with different content after 2 seconds → upsert allowed
- Enables safe retry logic without duplicate data

---

## Idempotency Guarantees

Request keys enable safe retries:

```bash
# First request
curl -X POST http://localhost:8080/api/applications \
  -H "X-API-Key: $API_KEY" \
  -d '{
    "requestKey": "acme__senior-engineer__2026-04-13",
    "companyName": "Acme Corp",
    ...
  }'
# Returns 201 (created)

# Identical retry (network timeout scenario)
curl -X POST http://localhost:8080/api/applications \
  -H "X-API-Key: $API_KEY" \
  -d '{
    "requestKey": "acme__senior-engineer__2026-04-13",
    "companyName": "Acme Corp",
    ...
  }'
# Returns 200 (replayed existing record)
# Same record ID, no duplicate created
```

---

## Versioning

Currently on API `v1` (implicit in URLs). Breaking changes will be announced with advance notice and a new version endpoint (`/api/v2/...`).
