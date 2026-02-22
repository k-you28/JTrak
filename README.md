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

## Run with Docker

Build image:

```bash
docker build -t job-application-tracker:latest .
```

Run container (persists H2 DB on host):

```bash
docker run --rm -p 8081:8081 -v "$(pwd)/data:/app/data" job-application-tracker:latest
```

Optional JVM tuning:

```bash
docker run --rm -p 8081:8081 -e JAVA_OPTS="-Xms256m -Xmx512m" -v "$(pwd)/data:/app/data" job-application-tracker:latest
```

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

## Test plan: basic to high load

### 1) Functional and regression tests (automated)

Run all Java tests:

```bash
./gradlew test
```

Coverage added in this repo:
- `JobApplicationServiceTest` (unit): key generation, idempotent replay, rate-limit guard, delete behavior.
- `ApplicationsApiIntegrationTest` (integration): API key auth enforcement, create/get/list, replay semantics.
- `WebUiDeleteIntegrationTest` (integration): delete selected row by id through web endpoint.

### 2) Quick smoke latency check (k6)

```bash
k6 run load/k6/smoke.js
```

Purpose:
- Low traffic sanity check
- Basic latency thresholds (`p95`, `p99`)
- API key creation + submit/list flow

### 3) Baseline concurrent load (k6)

```bash
k6 run load/k6/baseline-concurrency.js
```

Purpose:
- Ramp users from light to moderate concurrency
- Measure latency under sustained concurrent writes + reads
- Include replay requests to verify idempotency behavior under contention

### 4) High-stress load test (k6)

```bash
k6 run load/k6/high-stress.js
```

Purpose:
- Push to higher virtual-user levels
- Observe throughput ceilings, error rate, and tail latency degradation

### 5) No-k6 fallback concurrency test (curl + xargs)

```bash
./load/scripts/curl-concurrency.sh http://localhost:8081 500 50
```

Arguments:
1. Base URL (default `http://localhost:8081`)
2. Total requests (default `200`)
3. Parallel workers/concurrency (default `20`)

Outputs:
- Average latency
- P50/P95/P99 latency

### Suggested execution order

1. `./gradlew test`
2. `k6 run load/k6/smoke.js`
3. `k6 run load/k6/baseline-concurrency.js`
4. `k6 run load/k6/high-stress.js`

If a stage fails, fix before moving up to the next stage.

## Reference

This project is based on the patterns in the parent **ky_demo** webhook ingest service (idempotency, rate limit, DLQ, API key filter). Keep that repo as reference when changing this one.
