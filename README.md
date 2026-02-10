# Job Application Tracker

A small Spring Boot app to track job applications (company, position, date applied, status, notes). Uses the same patterns as the webhook ingest project: idempotency by request key, rate limiting, dead-letter queue for failures, and API key authentication for the REST API.

## Run

**Option 1 – IDE:** Open the `job-application-tracker` folder as a project and run `JobTrackerApplication`.

**Option 2 – Gradle:** From the `job-application-tracker` directory, if you have Gradle installed run `gradle wrapper` once, then:

```bash
./gradlew bootRun
```

**Option 3 – Gradle installed:** From `job-application-tracker`, run `gradle bootRun`.

App runs on **http://localhost:8081** (port 8081 so it doesn't clash with the reference webhook app on 8080).

## Web UI (no API key)

- **http://localhost:8081/** – List all applications  
- **http://localhost:8081/add** – Add an application  
- **http://localhost:8081/view?requestKey=...** – View one application  

The UI talks to the service directly (no API key). For production you’d add auth to the UI.

## REST API (API key required)

All `/api/applications` requests require a valid API key.

**Create an API key:**

```bash
curl -X POST http://localhost:8081/admin/api-keys \
  -H "Content-Type: application/json" \
  -d '{"name": "My key"}'
```

Use the returned `keyValue` in the `X-API-Key` header.

**Submit an application:**

```bash
curl -X POST http://localhost:8081/api/applications \
  -H "Content-Type: application/json" \
  -H "X-API-Key: jt_<your-key>" \
  -d '{
    "requestKey": "acme_senior_engineer_2026-01-26",
    "companyName": "Acme Inc",
    "positionTitle": "Senior Engineer",
    "dateApplied": "2026-01-26",
    "status": "APPLIED",
    "source": "LinkedIn",
    "notes": "Referral from Jane"
  }'
```

**Get by request key:**

```bash
curl "http://localhost:8081/api/applications/acme_senior_engineer_2026-01-26" \
  -H "X-API-Key: jt_<your-key>"
```

**List all:**

```bash
curl "http://localhost:8081/api/applications" \
  -H "X-API-Key: jt_<your-key>"
```

## Data model

- **requestKey** – Idempotency key (e.g. `company_position_YYYY-MM-DD`). Same key + same content = replay; same key + different content within 2s = rate limited.
- **companyName**, **positionTitle**, **dateApplied** – Required.
- **status** – APPLIED, INTERVIEWING, OFFER, REJECTED (default APPLIED).
- **source** – e.g. LinkedIn, company site, referral.
- **notes** – Free text.

## Tech

- Spring Boot 3.2, Java 17  
- JPA/H2 (PostgreSQL-ready via config)  
- Thymeleaf for the UI  
- API key auth via filter; admin endpoints for key create/deactivate  

## Reference

This project is based on the patterns in the parent **ky_demo** webhook ingest service (idempotency, rate limit, DLQ, API key filter). Keep that repo as reference when changing this one.
