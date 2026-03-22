# Production Readiness Plan

**App:** Job Application Tracker
**Deployment target:** Railway
**Date:** 2026-03-22

This is an ordered list of tasks to make the application safe, stable, and observable for real users on Railway.
Each section is ranked by severity. Do **not** accept real user traffic until all `BLOCKER` items are resolved.

---

## Tier 0 — Security Blockers (Do these before anything else)

### 0.1 Rotate Exposed API Credentials — BLOCKER
Your `config/secrets.properties` file contains real API keys (Anthropic, Adzuna). Although it is gitignored, if it was ever committed accidentally — even for one second — those keys are compromised.

**Action required:**
1. Go to [console.anthropic.com](https://console.anthropic.com) → API Keys → revoke the current key → create a new one
2. Go to [developer.adzuna.com](https://developer.adzuna.com) → your app → regenerate App Key
3. Update your Railway environment variables with the new keys (see §1.2)
4. Delete `config/secrets.properties` locally after you have set the Railway env vars — you no longer need the file

**Verification:**
```bash
git log --all --full-history -- "config/secrets.properties"   # should show no commits
git grep -r "sk-ant-api" -- "*.properties" "*.yml" "*.env"   # should return nothing
```

---

### 0.2 Move Legacy Password Hash to Environment Variable — BLOCKER
`application.properties` line 39 contains a hardcoded BCrypt hash for the internal legacy API account. Even though it is not a real user password, it should not be in source control.

**Action required:**

In `application.properties`, change:
```properties
app.ownership.legacy-password-hash=$2a$10$7EqJtq98hPqEX7fNZaFWoO6P6QF6UVx/FuWRzE7dOjIvmhjYQdkf.
```
to:
```properties
app.ownership.legacy-password-hash=${LEGACY_ACCOUNT_PASSWORD_HASH}
```

Then on Railway, add a new environment variable `LEGACY_ACCOUNT_PASSWORD_HASH` with a freshly generated BCrypt hash. Generate one with:
```bash
htpasswd -bnBC 10 "" "some-random-string-only-you-know" | tr -d ':\n' | sed 's/$apr1/$2a/'
# Or use an online BCrypt generator and store the result in Railway
```

---

## Tier 1 — Infrastructure (Required before launch)

### 1.1 Provision PostgreSQL on Railway — REQUIRED
The default configuration uses H2 (file-based). H2 is not suitable for production — it has no concurrent write support, no network access, and data is lost when the container restarts without a persistent volume.

**Action required:**
1. In your Railway project dashboard: click **+ New** → **Database** → **PostgreSQL**
2. Railway will provision a managed PostgreSQL instance
3. Note the **connection variables** Railway provides: `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER`, `PGPASSWORD`

**Important:** The `user_resumes` table uses `BLOB` in the H2 migration but PostgreSQL requires `BYTEA`. Before running the app against PostgreSQL, a database migration is needed (see §1.5).

---

### 1.2 Set All Required Environment Variables on Railway — REQUIRED

In Railway, go to your service → **Variables** tab. Add all of the following:

| Variable | Value | Notes |
|----------|-------|-------|
| `SPRING_PROFILES_ACTIVE` | `prod` | Activates `application-prod.properties` |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://${{Postgres.PGHOST}}:${{Postgres.PGPORT}}/${{Postgres.PGDATABASE}}` | Use Railway variable references |
| `SPRING_DATASOURCE_USERNAME` | `${{Postgres.PGUSER}}` | Railway variable reference |
| `SPRING_DATASOURCE_PASSWORD` | `${{Postgres.PGPASSWORD}}` | Railway variable reference |
| `ANTHROPIC_API_KEY` | `sk-ant-api03-...` | New key from §0.1 |
| `ADZUNA_APP_ID` | `your-app-id` | From Adzuna dashboard |
| `ADZUNA_APP_KEY` | `your-app-key` | New key from §0.1 |
| `APP_VERIFICATION_BASE_URL` | `https://your-app.up.railway.app` | Your Railway public URL |
| `SMTP_HOST` | e.g. `smtp.gmail.com` or `smtp.resend.com` | See §1.3 |
| `SMTP_PORT` | `587` | Standard STARTTLS port |
| `SMTP_USERNAME` | your SMTP login | See §1.3 |
| `SMTP_PASSWORD` | your SMTP password/token | See §1.3 |
| `LEGACY_ACCOUNT_PASSWORD_HASH` | BCrypt hash | From §0.2 |

Railway variable references (`${{Postgres.PGHOST}}`) link automatically to your PostgreSQL service and update if credentials rotate.

---

### 1.3 Configure Real Email Delivery — REQUIRED
By default the app uses `app.email.provider=smtp` but without valid SMTP credentials, email verification links are only logged to stdout. Users cannot verify their accounts.

**Recommended options:**

**Option A: Resend (simplest, generous free tier)**
1. Sign up at [resend.com](https://resend.com)
2. Verify your sending domain (or use their sandbox for testing)
3. Get an API key; use SMTP bridge: `smtp.resend.com`, port `587`, username = `resend`, password = your API key

**Option B: SendGrid**
1. Sign up at [sendgrid.com](https://sendgrid.com)
2. Create an API key with "Mail Send" permission
3. SMTP: `smtp.sendgrid.net`, port `587`, username = `apikey`, password = your API key

**Option C: Gmail (for personal use only)**
1. Enable 2FA on your Gmail account
2. Create an App Password at `myaccount.google.com/apppasswords`
3. SMTP: `smtp.gmail.com`, port `587`, username = your email, password = App Password

After configuring, set these in Railway (see §1.2) and update `app.email.from` to a verified sender address.

---

### 1.4 Fix the BLOB→BYTEA Schema Issue for PostgreSQL — REQUIRED
The V1 migration creates `user_resumes.pdf_bytes` as `BLOB`, which is an H2 type. PostgreSQL requires `BYTEA`. This must be resolved before Flyway can initialize the schema.

**The problem:**
- `V1__initial_schema.sql` uses `BLOB` — valid in H2, invalid in PostgreSQL
- The `UserResume` entity uses `@Lob byte[]` — Hibernate maps this to `BLOB` dialect type
- Hibernate's schema validation will fail if the DB column type doesn't match

**Solution A (cleanest, for a fresh Railway PostgreSQL with no data):**

1. Create a new migration `V2__postgres_bytea_fix.sql` (only needed if V1 already ran on this PostgreSQL instance):

```sql
-- V2: Fix BLOB column to BYTEA on PostgreSQL.
-- Only required if V1 was previously applied before this fix.
-- Skip this if the PostgreSQL instance is brand new (V1 will use the correct type after §1.4 Step 2).
ALTER TABLE user_resumes ALTER COLUMN pdf_bytes TYPE BYTEA USING pdf_bytes;
```

2. Update `V1__initial_schema.sql` to use `BYTEA` for all new deployments:
   - Change `pdf_bytes BLOB NOT NULL` → `pdf_bytes BYTEA NOT NULL`
   - Delete `data/jobtracker.mv.db` locally to reset the H2 database (it's gitignored, local data only)

3. Update `UserResume` entity annotation to match:
   ```java
   // Current (in entity):
   @Lob
   private byte[] pdfBytes;

   // Change to (works for both H2 and PostgreSQL):
   @Column(columnDefinition = "bytea")
   private byte[] pdfBytes;
   ```

4. Update test datasource URLs to use H2 PostgreSQL compatibility mode:
   ```
   jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DEFAULT_NULL_ORDERING=HIGH
   ```

**Solution B (quick workaround, no migration needed):**
Configure Hibernate to skip schema validation (not recommended long-term):
```properties
# In application-prod.properties — temporary workaround only
spring.jpa.hibernate.ddl-auto=none
```

---

### 1.5 Configure Railway Health Check — REQUIRED
Railway uses a health check to know when your service is ready and to detect crashes. Without it, Railway routes traffic to the container before Spring Boot has finished starting up.

**Action required:**

In Railway, go to your service → **Settings** → **Health Check**:
- **Path:** `/actuator/health`
- **Port:** `8081` (or `${PORT}` if you use Railway's dynamic port)
- **Timeout:** `30` seconds (Spring Boot startup can be slow on cold start)
- **Interval:** `10` seconds

The `/actuator/health` endpoint is already configured in `application.properties` and allowed without authentication in `SecurityConfig.java`.

---

### 1.6 Set Railway PORT Environment Variable — REQUIRED
Railway injects a `PORT` environment variable at runtime. The `application-prod.properties` file already uses `server.port=${PORT:8081}`, so this is handled — but you must expose port `8081` in Railway's settings if you use a fixed port, OR let Railway assign the port and ensure `PORT` is passed through.

**For simplest setup:** In Railway service settings, set **Start Command** to the default (Docker entrypoint is already set), and verify the port exposed is `8081`.

---

## Tier 2 — Observability (Do these within first week of production traffic)

### 2.1 Add Structured Logging with Logback — HIGH
Currently all log output is unstructured text. When debugging issues in Railway's log viewer, structured JSON logs are far easier to search and filter.

**Action required:**

Create `src/main/resources/logback-spring.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <springProfile name="prod">
        <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
            <encoder class="ch.qos.logback.classic.encoder.PatternLayoutEncoder">
                <pattern>{"time":"%d{ISO8601}","level":"%-5level","logger":"%logger{36}","msg":"%msg"}%n</pattern>
            </encoder>
        </appender>
        <root level="WARN">
            <appender-ref ref="STDOUT"/>
        </root>
        <logger name="com.kevin" level="INFO"/>
        <logger name="org.flywaydb" level="INFO"/>
    </springProfile>

    <springProfile name="!prod">
        <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
            <encoder>
                <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
            </encoder>
        </appender>
        <root level="INFO">
            <appender-ref ref="STDOUT"/>
        </root>
    </springProfile>
</configuration>
```

---

### 2.2 Add Request/Response Logging Middleware — HIGH
No HTTP request logging exists today. You cannot tell from logs what endpoints are being called or how fast they respond.

**Action required:**

Add to `application-prod.properties`:
```properties
# Log all HTTP requests (method, path, status, duration)
logging.level.org.springframework.web.servlet.DispatcherServlet=DEBUG
```

Or better, create a `RequestLoggingFilter` bean:
```java
@Bean
public CommonsRequestLoggingFilter requestLoggingFilter() {
    CommonsRequestLoggingFilter filter = new CommonsRequestLoggingFilter();
    filter.setIncludeQueryString(true);
    filter.setIncludeClientInfo(true);
    filter.setMaxPayloadLength(0); // don't log request bodies (contains user data)
    return filter;
}
```

---

### 2.3 Add Micrometer Metrics Export — MEDIUM
Spring Boot Actuator includes Micrometer but metrics are not exported anywhere. You cannot see JVM heap, request latency, or DB pool usage without this.

**Action required:**

Add to `build.gradle`:
```groovy
implementation 'io.micrometer:micrometer-registry-prometheus'
```

Add to `application-prod.properties`:
```properties
management.endpoints.web.exposure.include=health,info,prometheus
management.metrics.export.prometheus.enabled=true
```

Then expose `/actuator/prometheus` and scrape with Railway's metrics integration, or use an external service like [Grafana Cloud free tier](https://grafana.com/products/cloud/).

**Note:** If you add Prometheus, protect the `/actuator/prometheus` endpoint with HTTP Basic auth or restrict it to internal network only. Do not leave it publicly accessible.

---

### 2.4 Set Up Error Alerting — MEDIUM
No alerting exists today. If the app crashes, you won't know until a user reports it.

**Minimum viable option:**
1. In Railway: go to your service → **Observability** → **Logs** → set up a log alert for "ERROR" patterns
2. Or: set a `DEPLOY_WEBHOOK_URL` (already supported in `deploy.yml`) pointing to a Slack channel webhook to be notified on every deploy

**Recommended:**
- Sign up for [Sentry.io](https://sentry.io) free tier
- Add `sentry-spring-boot-starter` to `build.gradle`
- Set `SENTRY_DSN` environment variable on Railway
- Sentry captures all unhandled exceptions with stack traces and notifies via email/Slack

---

## Tier 3 — Data Safety (Do these within first week)

### 3.1 Configure PostgreSQL Backups on Railway — REQUIRED
Railway's managed PostgreSQL has point-in-time recovery on paid plans but no automatic backups on the free tier.

**Action required:**

**If on Railway Hobby/Pro plan:**
1. Go to your PostgreSQL service → **Settings** → enable **Point-in-Time Recovery** (if available)
2. Set a backup retention window (minimum: 7 days)

**If on Railway free tier or for extra safety:**
Set up a scheduled backup job. Example using `pg_dump` via a Railway cron job or GitHub Action:
```yaml
# .github/workflows/backup.yml
name: Database Backup
on:
  schedule:
    - cron: '0 2 * * *'  # Daily at 2 AM UTC
jobs:
  backup:
    runs-on: ubuntu-latest
    steps:
      - name: Dump database
        run: pg_dump ${{ secrets.DATABASE_URL }} | gzip > backup-$(date +%Y%m%d).sql.gz
      # Add step to upload to S3/GCS/R2
```

---

### 3.2 Add Token Cleanup Scheduled Job — LOW
`email_verification_tokens` table accumulates expired/used tokens forever. For a production app with real signups, this becomes a slow, unbounded table.

**Action required:**

Add to `EmailVerificationService`:
```java
// Run nightly at 3 AM
@Scheduled(cron = "0 0 3 * * *")
public void purgeExpiredTokens() {
    int deleted = tokenRepository.deleteByExpiresAtBefore(Instant.now().minus(7, ChronoUnit.DAYS));
    log.info("Purged {} expired email verification tokens", deleted);
}
```

Add to `EmailVerificationTokenRepository`:
```java
@Modifying
@Query("DELETE FROM EmailVerificationToken t WHERE t.expiresAt < :cutoff")
int deleteByExpiresAtBefore(@Param("cutoff") Instant cutoff);
```

---

## Tier 4 — Security Hardening (Do before accepting public traffic)

### 4.1 Add Global Rate Limiting — HIGH
The current rate limiting (2-second window per IP + requestKey) only applies to the `POST /api/applications` endpoint. The registration and login endpoints have no rate limiting — an attacker can brute-force passwords or spam verification emails.

**Action required:**

Add Spring Boot's built-in request rate limiter (or use `bucket4j`):

Add to `build.gradle`:
```groovy
implementation 'com.github.vladimir-bukhtoyarov:bucket4j-core:8.14.0'
```

Create a `RateLimitingFilter` or use an AOP approach. At minimum, protect:
- `POST /login` — max 10 attempts per IP per minute
- `POST /register` — max 5 registrations per IP per hour
- `POST /resend-verification` — max 3 per email per hour

**Quick alternative:** Configure Railway's built-in edge rate limiting (if on Pro plan) at the load balancer level.

---

### 4.2 Validate File Uploads — HIGH
The resume upload endpoints check that a file is not empty and has a `.pdf` extension, but do not validate the actual file content (magic bytes). A malicious upload could pass the extension check.

**Action required:**

In `HrLensService.uploadAndAnalyze()` and `ResumeParserService.parse()`, add magic byte validation:
```java
private void validatePdf(MultipartFile file) throws IOException {
    byte[] header = new byte[4];
    try (InputStream in = file.getInputStream()) {
        if (in.read(header) < 4 || header[0] != 0x25 || header[1] != 0x50
                || header[2] != 0x44 || header[3] != 0x46) { // %PDF
            throw new IllegalArgumentException("File is not a valid PDF");
        }
    }
}
```

Also enforce allowed MIME types:
```java
private static final Set<String> ALLOWED_TYPES = Set.of("application/pdf",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
if (!ALLOWED_TYPES.contains(file.getContentType())) {
    throw new IllegalArgumentException("Only PDF and DOCX files are accepted");
}
```

---

### 4.3 Add Input Length Validation at Controller Layer — MEDIUM
Several fields have no length validation at the controller/DTO level, only at the DB schema level (which gives unhelpful 500 errors to users).

**Action required:**

Add `@Valid` + Bean Validation annotations to `JobApplicationRequest`:
```java
public class JobApplicationRequest {
    @NotBlank(message = "Company name is required")
    @Size(max = 255, message = "Company name must be 255 characters or less")
    private String companyName;

    @NotBlank(message = "Position title is required")
    @Size(max = 255, message = "Position title must be 255 characters or less")
    private String positionTitle;

    @NotNull(message = "Date applied is required")
    private LocalDate dateApplied;

    @Size(max = 255)
    private String notes;
    // ...
}
```

Add `@Valid` to controller method parameters and a `@ControllerAdvice` that returns user-friendly validation error responses.

---

### 4.4 Restrict H2 Console to Local Profile Only — MEDIUM
The H2 console is currently enabled in the default `application.properties`. If a deployment accidentally runs without the prod profile active (e.g., env var misconfiguration), the H2 console will be publicly accessible.

**Action required:**

In `application.properties`, change:
```properties
spring.h2.console.enabled=true
```
to:
```properties
spring.h2.console.enabled=false
```

Then add a `application-local.properties` (or `application-dev.properties`) file with:
```properties
spring.h2.console.enabled=true
```

This ensures H2 console is only accessible when the `local` or `dev` profile is explicitly activated, never in production.

---

### 4.5 Configure CORS for API Endpoints — MEDIUM
No explicit CORS configuration exists. If anyone builds a browser-based client on a different origin (e.g., a Chrome extension), requests will be blocked by the browser.

**Action required:**

Add to `SecurityConfig.java`:
```java
@Bean
public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowedOrigins(List.of(
        "https://your-app.up.railway.app"  // your Railway URL
    ));
    config.setAllowedMethods(List.of("GET", "POST", "DELETE", "OPTIONS"));
    config.setAllowedHeaders(List.of("Content-Type", "X-API-Key", "Authorization"));
    config.setMaxAge(3600L);
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/api/**", config);
    return source;
}
```

And in the security chain:
```java
.cors(cors -> cors.configurationSource(corsConfigurationSource()))
```

---

## Tier 5 — Code Quality (Do before second major feature)

### 5.1 Migrate Tests to Testcontainers — MEDIUM
All integration tests currently use H2 (in-memory). H2 dialect differs from PostgreSQL in subtle ways (type handling, case sensitivity, index behaviour). Tests that pass on H2 can fail on production PostgreSQL.

**Action required:**

Add to `build.gradle`:
```groovy
testImplementation 'org.springframework.boot:spring-boot-testcontainers'
testImplementation 'org.testcontainers:postgresql:1.19.7'
```

Create `src/test/resources/application-test.properties`:
```properties
spring.datasource.url=jdbc:tc:postgresql:15:///jobtracker?TC_REUSABLE=true
spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver
spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect
spring.h2.console.enabled=false
```

Remove the inline `@TestPropertySource` datasource overrides from all integration tests.

This means tests run against the same PostgreSQL version as production. The BLOB→BYTEA issue (§1.4) and any other dialect differences will be caught in CI rather than production.

**Prerequisite:** Docker must be available in the CI environment (it is, in GitHub Actions runners).

---

### 5.2 Add `@ControllerAdvice` for Global Exception Handling — MEDIUM
Currently, unhandled exceptions return a Spring default error page (white label error) to users. This is confusing and leaks stack trace hints.

**Action required:**

Create `GlobalExceptionHandler.java`:
```java
@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public String handleValidationError(IllegalArgumentException ex, RedirectAttributes attrs) {
        attrs.addFlashAttribute("error", ex.getMessage());
        return "redirect:/";
    }

    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public String handleAccessDenied() {
        return "error/403";
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public String handleGenericError(Exception ex, HttpServletRequest req) {
        log.error("Unhandled exception on {}: {}", req.getRequestURI(), ex.getMessage(), ex);
        return "error/500";
    }
}
```

Also create minimal Thymeleaf error pages: `templates/error/403.html`, `templates/error/404.html`, `templates/error/500.html`.

---

### 5.3 Add OpenAPI / Swagger Documentation — LOW
The REST API has no machine-readable documentation. This matters when building integrations.

**Action required:**

Add to `build.gradle`:
```groovy
implementation 'org.springdoc:springdoc-openapi-starter-webmvc-ui:2.4.0'
```

Add to `application.properties`:
```properties
springdoc.api-docs.path=/api-docs
springdoc.swagger-ui.path=/swagger-ui
springdoc.swagger-ui.enabled=false  # disabled in prod, enable in dev
```

Add `@Operation`, `@Parameter`, `@ApiResponse` annotations to controllers.

---

## Tier 6 — Feature Completeness (Ongoing)

These items are tracked in `TODO.md`. Reference that file for the build order. Production readiness does not require these — they are product improvements.

| Item | Phase in TODO.md |
|------|-----------------|
| Search, filter, sort on dashboard | Phase 1.1 |
| Pagination | Phase 1.1 |
| Activity timeline | Phase 2.2 |
| Analytics dashboard | Phase 4.1 |
| CSV / JSON export | Phase 4.2 |
| API key audit logs | Phase 3.2 |
| Duplicate detection | Phase 2.3 |

---

## Summary Checklist

### Must complete before accepting real users

- [ ] **0.1** Rotate Anthropic + Adzuna API keys
- [ ] **0.2** Move legacy password hash to env var
- [ ] **1.1** Provision PostgreSQL on Railway
- [ ] **1.2** Set all required environment variables on Railway
- [ ] **1.3** Configure real SMTP email delivery
- [ ] **1.4** Fix BLOB→BYTEA schema issue for PostgreSQL
- [ ] **1.5** Configure Railway health check at `/actuator/health`
- [ ] **1.6** Verify Railway port configuration

### Complete within first week of real traffic

- [ ] **2.1** Add structured logging (Logback)
- [ ] **2.2** Add request/response logging
- [ ] **2.3** Export metrics (Prometheus or Micrometer)
- [ ] **2.4** Set up error alerting (Sentry or Railway alerts)
- [ ] **3.1** Configure PostgreSQL backups
- [ ] **3.2** Add expired token cleanup job

### Complete before scaling or public launch

- [ ] **4.1** Global rate limiting on auth endpoints
- [ ] **4.2** Magic-byte file upload validation
- [ ] **4.3** Input length validation at controller layer
- [ ] **4.4** Restrict H2 console to local profile only
- [ ] **4.5** Configure CORS for API endpoints
- [ ] **5.1** Migrate tests to Testcontainers + PostgreSQL
- [ ] **5.2** Global exception handler + error pages
- [ ] **5.3** OpenAPI documentation
