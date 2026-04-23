# Architecture Overview

**Last Updated:** 2026-04-13

**Tech Stack:** Spring Boot 3.2 / Java 17 | PostgreSQL / H2 | Thymeleaf | Claude Haiku (AI)

---

## Directory Structure

```
src/main/java/com/kevin/jobtracker/
├── controller/              # HTTP endpoints (web + REST API)
│   ├── ApplicationsController.java    # POST/GET/PATCH/DELETE applications
│   ├── ResumeController.java          # POST /api/resume/parse
│   ├── WebUiController.java           # GET /home, /add, etc. (Thymeleaf)
│   ├── AuthController.java            # GET /login, /register
│   ├── MetricsController.java         # GET /api/metrics
│   └── ApiKeyController.java          # POST/DELETE /admin/api-keys
├── service/                 # Business logic
│   ├── JobApplicationService.java           # Core application submission + idempotency
│   ├── ResumeParserService.java             # Extract text + Claude Haiku parsing
│   ├── HrLensService.java                   # Resume analysis against market data
│   ├── JobMarketAnalyticsService.java       # Adzuna API polling + trend calculation
│   ├── SkillDemandAnalyticsService.java     # Extract top skills from job postings
│   ├── UserAccountService.java              # User registration + password hashing
│   ├── ApiKeyService.java                   # API key validation + generation
│   ├── FollowUpService.java                 # Detect stale apps + draft generation
│   ├── DeadLetterService.java               # Record failed submissions
│   ├── HackerNewsService.java               # Fetch latest tech news
│   └── JobApplicationOwnershipBackfill.java # Migration: backfill missing ownership
├── entity/                  # JPA models (mapped to database)
│   ├── JobApplication.java              # Job applications + follow-up drafts
│   ├── UserAccount.java                 # User logins + password hashes
│   ├── ApiKey.java                      # API key credentials
│   ├── UserResume.java                  # PDF + analysis text per user
│   ├── JobMarketSnapshot.java           # Market data + trend snapshots
│   ├── SkillDemandSnapshot.java         # Top skills per role
│   └── DeadLetterEvent.java             # Failed submission records
├── repository/              # Spring Data JPA interfaces (data access)
│   ├── JobApplicationRepository.java
│   ├── UserAccountRepository.java
│   ├── ApiKeyRepository.java
│   ├── UserResumeRepository.java
│   ├── JobMarketSnapshotRepository.java
│   ├── SkillDemandSnapshotRepository.java
│   └── DeadLetterRepository.java
├── model/                   # DTOs + request/response objects
│   ├── JobApplicationRequest.java  # POST body for /api/applications
│   ├── ResumeDataDto.java          # Parsed resume fields
│   ├── HrLensAnalysisDto.java      # HR Lens JSON response
│   ├── FollowUpItem.java           # Stale app for UI display
│   ├── RegistrationRequest.java    # POST body for /register
│   └── ErrorResponse.java          # Standard error envelope
├── security/                # Authentication + authorization
│   ├── ApiKeyAuthenticationFilter.java  # Intercept X-API-Key header
│   └── AuthenticationFailureRoutingHandler.java
├── config/                  # Spring beans + configuration
│   ├── SecurityConfig.java      # Spring Security chain + CSP headers
│   └── HttpClientConfig.java    # RestTemplate beans (Claude, Adzuna, etc.)
├── metrics/                 # Observability
│   └── ApplicationMetrics.java  # Counter for created/replayed/rate-limited/dead-letter
└── exception/               # Error handling
    └── GlobalExceptionHandler.java
```

---

## Core Patterns

### 1. Idempotency & Request Keys

**Location:** `src/main/java/com/kevin/jobtracker/service/JobApplicationService.java`

**Design:**

```
requestKey = idempotency key for application submissions
Default (if omitted): "{company-slug}__{position-slug}__{date-applied}"
```

**Logic:**

1. Incoming POST request with `requestKey`
2. Look up existing application with same `requestKey` + `userId`
3. If exists + same content → return 200 (replay), increment metrics.replayed()
4. If exists + different content + within 2s → return 429 (rate limit)
5. If exists + different content + after 2s → upsert (update existing), increment metrics.created()
6. If not exists → create new, increment metrics.created()
7. If any exception → record in DeadLetterEvent, return 500, increment metrics.deadLettered()

**Example:**
```bash
# First submission
curl -X POST /api/applications \
  -H "X-API-Key: ..." \
  -d '{"requestKey": "acme__engineer__2026-04-13", "companyName": "Acme", ...}'
# Returns 201, creates record

# Retry (same key, same content) — safe
curl -X POST /api/applications \
  -H "X-API-Key: ..." \
  -d '{"requestKey": "acme__engineer__2026-04-13", "companyName": "Acme", ...}'
# Returns 200, no duplicate created
```

---

### 2. Multi-User Ownership (Legacy Pattern)

**Location:**
- `src/main/java/com/kevin/jobtracker/service/JobApplicationService.java` → `resolveOwnerUserId()`
- `src/main/java/com/kevin/jobtracker/controller/ApplicationsController.java` → `ownerEmail()`

**Design:**

Each `JobApplication` has a `userId` (foreign key to `UserAccount.id`).

- **Authenticated web users:** Own their own applications (scoped by email)
- **API-key requests:** No authenticated principal → pinned to synthetic account `legacy-api@jobtracker.local`
  - Auto-created on first request if doesn't exist
  - Enables backwards compatibility with pre-authentication data
  - Applications shared across all API-key users (same legacy owner)

**Code:**
```java
private String resolveOwnerUserId(String ownerEmail) {
    if (ownerEmail == null || ownerEmail.isBlank()) {
        return getOrCreateLegacyOwner().getId();  // API-key path
    }
    String normalized = ownerEmail.trim().toLowerCase(Locale.ROOT);
    UserAccount account = userAccountRepository.findByEmail(normalized)
        .orElseThrow(() -> new IllegalArgumentException("Authenticated user account not found"));
    return account.getId();  // Web UI path
}
```

---

### 3. Rate Limiting (Per-IP + Per-RequestKey)

**Location:** `src/main/java/com/kevin/jobtracker/service/JobApplicationService.java` → `submit()`

**Two-Layer Strategy:**

1. **Per-IP rate limiting (2-second window):**
   ```java
   Optional<JobApplication> lastOpt = applicationRepository
       .findTopByClientIpAndUserIdOrderByCreatedAtDesc(clientIp, ownerUserId);
   if (lastOpt.isPresent() && lastOpt.get().getCreatedAt().plusSeconds(2).isAfter(now)) {
       metrics.recordRateLimited();
       throw new IllegalStateException("Rate limit exceeded");
   }
   ```

2. **Per-RequestKey rate limiting (content change detection):**
   ```java
   if (existingOpt.isPresent()) {
       JobApplication existing = existingOpt.get();
       if (isSameContent(existing, req)) {
           metrics.recordReplayed();
           return existing;  // Idempotent replay
       }
       if (existing.getCreatedAt().plusSeconds(2).isAfter(now)) {
           metrics.recordRateLimited();
           throw new IllegalStateException("Rate limit exceeded - attempted overwrite too soon");
       }
       // After 2 seconds, allow upsert
       updateFromRequest(existing, req);
       existing.setCreatedAt(now);
       return applicationRepository.save(existing);
   }
   ```

**IP Extraction:**
```java
private String extractClientIp(HttpServletRequest request) {
    String remoteAddr = request.getRemoteAddr();
    if (isTrustedProxy(remoteAddr)) {  // 127.0.0.1, ::1, etc.
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();  // Reverse proxy in front
        }
    }
    return remoteAddr;
}
```

---

### 4. AI-Powered Resume Parsing

**Location:**
- Resume parser: `src/main/java/com/kevin/jobtracker/service/ResumeParserService.java`
- HR Lens: `src/main/java/com/kevin/jobtracker/service/HrLensService.java`

#### Resume Parser (`POST /api/resume/parse`)

**Flow:**
1. Receive PDF or DOCX file upload
2. Extract text (PDFBox for PDF, POI for DOCX)
3. Truncate to 8,000 characters (token budget)
4. Call Claude Haiku with structured prompt
5. Parse JSON response (strips markdown code fences if needed)
6. Return `ResumeDataDto` (currentTitle, targetTitle, summary, topSkills, notes)

**Prompt Engineering:**
```
"Extract information from the following resume text and return ONLY valid JSON..."
{
  "currentTitle": "...",
  "targetTitle": "...",
  "summary": "2-3 sentence professional summary...",
  "topSkills": ["skill1", "skill2", "skill3"],
  "notes": "Pre-formatted application notes..."
}
```

#### HR Lens (`POST /api/hr-lens/upload`)

**Flow:**
1. Authenticated user uploads PDF
2. Extract resume text + fetch application history + fetch top market skills
3. Build comprehensive prompt with:
   - Resume (truncated to 8,000 chars)
   - Application history (last 25 apps) with counts (interviews, offers, rejections)
   - Live skill demand data (top 8 in-demand skills for software engineer role)
4. Call Claude Haiku with "brutally honest senior recruiter" persona
5. Parse JSON response with fallback to raw text on error
6. Store in `UserResume.analysisText` (replaces previous analysis)

**Prompt Engineering:**
```
"You are a brutally honest senior technical recruiter with 15 years of experience..."
{
  "pros": ["strength 1", ...],
  "cons": ["weakness 1", ...],
  "improvements": [{"title": "...", "description": "..."}],
  "conclusion": "2-3 sentence verdict"
}
```

**Error Handling:**
- Missing `ANTHROPIC_API_KEY` → 400 with clear message
- PDF extraction failure → 400
- Claude API timeout/error → 500
- Invalid JSON response → fallback DTO with raw text in `conclusion`

---

### 5. Market Intelligence (Job Market + Skill Demand)

**Location:**
- Job Market: `src/main/java/com/kevin/jobtracker/service/JobMarketAnalyticsService.java`
- Skill Demand: `src/main/java/com/kevin/jobtracker/service/SkillDemandAnalyticsService.java`

#### Job Market Analytics

**Data Source:** Adzuna API (`https://api.adzuna.com/v1/api/jobs/us/search`)

**Config Keys:**
```properties
app.market.enabled=true                          # Enable/disable market polling
app.market.query=software                        # Search query
app.market.page-start=1                          # Starting page
app.market.page-size=100                         # Results per page
app.adzuna.base-url=https://api.adzuna.com/...  # API endpoint
app.adzuna.app-id=<your-app-id>                 # Adzuna credentials
app.adzuna.app-key=<your-app-key>
```

**Polling Strategy:**
1. On startup (`ApplicationReadyEvent`), fetch job count for configured query
2. Use binary search to find last non-empty page + estimate total jobs
3. Every 15 minutes (configurable), repeat fetch
4. Store snapshot in `JobMarketSnapshot` with `pageStart`, `pageEnd`, `totalJobs`, `createdAt`

**Trend Calculation:**
```
Last 30 days of snapshots → compress to 1 per day → build SVG polyline
Max 360px wide × 120px tall with padding
Axis labels at 0%, 33%, 66%, 100% mark
Market health: +10% growth = "Healthy", -10% decline = "Poor", else = "OK"
```

**Entities:**
```java
@Entity
public class JobMarketSnapshot {
    String searchQuery;      // e.g. "software"
    int pageStart;          // First page fetched
    int pageEnd;            // Last page (calculated)
    int totalJobs;          // Estimated total job postings
    Instant createdAt;
    String errorMessage;    // Non-null on fetch failure
}
```

---

#### Skill Demand Analytics

**Data Source:** Same as job market (Adzuna API)

**Config Keys:**
```properties
app.market.enabled=true
app.skills.enabled=true
app.skills.keywords.software-engineer=python,java,go,...  # Configurable for SE
# Data Engineer & AI Engineer keywords hardcoded in service
app.skills.top-n=8                              # Top 8 skills per role
app.skills.dedupe-mode=fuzzy                    # Alias expansion
```

**Roles Supported:**
- `software engineer` (configurable keywords)
- `data engineer` (hardcoded keywords)
- `ai engineer` (hardcoded keywords)

**Algorithm:**
1. Fetch job postings from Adzuna (5 pages × 20 results)
2. Extract HTML job descriptions via jsoup
3. Search for keyword matches (case-insensitive, regex patterns)
4. Count occurrences per skill
5. Apply fuzzy dedupe (e.g., "power bi" + "powerbi" + "power-bi" → single skill)
6. Rank top N skills by occurrence count
7. Store in `SkillDemandSnapshot` per role, per fetch cycle

**Entities:**
```java
@Entity
public class SkillDemandSnapshot {
    String searchQuery;      // Role name (e.g. "software engineer")
    int page;               // Which page fetched from
    String skillName;       // e.g. "Python"
    int occurrenceCount;    // Times found in job postings
    int sampleJobs;         // Number of postings searched
    int rankPosition;       // Rank within top N
    Instant createdAt;
    String errorMessage;
}
```

**Dedupe Strategy (Fuzzy Matching):**
```java
// Example: "power bi" aliases → "powerbi", "power-bi"
private static final Map<String, String> DATA_ENGINEER_ALIASES = Map.of(
    "power bi", "powerbi,power-bi",
    "spark", "pyspark,apache spark",
    ...
);
// When counting "powerbi" in job description, increment "power bi" counter
```

**UI Integration:**
- Dropdown selector for role (SE / DE / AI)
- Top 8 skills displayed with occurrence counts
- Sorted by count (descending)
- Includes sample size (20 jobs per page × 5 pages = 100 postings)

---

### 6. Dead Letter Queue

**Location:** `src/main/java/com/kevin/jobtracker/service/DeadLetterService.java`

**Design:**

When any exception occurs during application submission, record failure:

```java
@Entity
public class DeadLetterEvent {
    String requestKey;          // Idempotency key
    String clientIp;            // Request origin
    String payload;             // Request data (company, position, date)
    String failureReason;       // Exception class + message
    Instant failedAt;
}
```

**Recording:**
```java
catch (Exception e) {
    metrics.recordDeadLetter();
    String payload = String.format("company=%s, position=%s, date=%s",
        req.getCompanyName(), req.getPositionTitle(), req.getDateApplied());
    deadLetterService.record(new DeadLetterEvent(
        key, clientIp, payload,
        e.getClass().getSimpleName() + ": " + e.getMessage()
    ));
    throw e;  // Re-throw to return 500 to client
}
```

**Use Cases:**
- Validation errors (missing company name)
- Database errors
- Rate limit rejections (429 recorded before throwing)
- API errors (Claude timeout)

**Access:** DB inspection required; no UI endpoint exposed yet.

---

### 7. Authentication & Authorization

**Location:**
- Config: `src/main/java/com/kevin/jobtracker/config/SecurityConfig.java`
- API Key filter: `src/main/java/com/kevin/jobtracker/security/ApiKeyAuthenticationFilter.java`
- User accounts: `src/main/java/com/kevin/jobtracker/service/UserAccountService.java`

#### Web UI Authentication

**Flow:**
1. Unauthenticated request → redirect to `/login`
2. User fills email + password → POST `/register` or POST (form login)
3. Registration → hash password with BCrypt, store in `UserAccount`, redirect to `/login`
4. Login → Spring Security validates, sets session cookie
5. All subsequent requests include session cookie

**Security Headers (CSP, HSTS, etc.):**
```java
http.headers(headers -> headers
    .contentSecurityPolicy(csp -> csp
        .policyDirectives("default-src 'self'; style-src 'self' 'unsafe-inline'; ...")
    )
    .httpStrictTransportSecurity(hsts -> hsts
        .maxAgeInSeconds(31536000)
        .includeSubDomains(true)
    )
    .xssProtection(xss -> {})
    .contentTypeOptions(ct -> {})
)
```

#### API Key Authentication

**Flow:**
1. Client sends `X-API-Key: jt_<key>` header (or `Authorization: ApiKey jt_<key>`)
2. `ApiKeyAuthenticationFilter` intercepts `/api/applications` GET/POST requests
3. Validates key in database via `ApiKeyService.isValid(apiKey)`
4. If valid + active → proceed with `null` authentication context (triggers legacy owner path)
5. If invalid → return 401 with "Valid API key required" message

**Key Generation:**
```java
String keyValue = "jt_" + UUID.randomUUID() + "_" + UUID.randomUUID();
// Format: jt_<uuid>_<uuid> for uniqueness + verification
```

**Key Storage:**
```java
@Entity
public class ApiKey {
    String keyValue;         // Unique, indexed
    String name;            // User-friendly name
    boolean active = true;  // Soft delete
    Instant createdAt;
    Instant lastUsedAt;     // For audit trails
}
```

#### Admin Endpoints

**Security:**
```java
.requestMatchers("/admin/api-keys/**").authenticated()
```

- Requires form-based authentication (web login)
- POST to create new key
- DELETE to deactivate key

---

### 8. Repository Pattern

All data access through Spring Data JPA interfaces:

```java
public interface JobApplicationRepository extends JpaRepository<JobApplication, String> {
    Optional<JobApplication> findByRequestKeyAndUserId(String requestKey, String userId);
    Optional<JobApplication> findByIdAndUserId(String id, String userId);
    Optional<JobApplication> findTopByClientIpAndUserIdOrderByCreatedAtDesc(String clientIp, String userId);
    List<JobApplication> findAllByUserIdOrderByDateAppliedDescCreatedAtDesc(String userId);
}
```

**Benefits:**
- No hardcoded SQL
- Type-safe queries
- Automatic pagination support
- Works with H2 (dev) and PostgreSQL (prod) without code changes

---

## Configuration

**File:** `src/main/resources/application.properties`

**Key Sections:**

```properties
# Database
spring.datasource.url=jdbc:postgresql://...
spring.jpa.hibernate.ddl-auto=validate

# Security
app.ownership.legacy-email=legacy-api@jobtracker.local
app.ownership.legacy-password-hash=<bcrypt-hash>

# Market Intelligence
app.market.enabled=true
app.market.query=software
app.adzuna.base-url=https://api.adzuna.com/v1/api/jobs/us/search
app.adzuna.app-id=<your-id>
app.adzuna.app-key=<your-key>

# Claude AI
anthropic.api-key=${ANTHROPIC_API_KEY}
anthropic.api-url=https://api.anthropic.com/v1/messages
anthropic.model=claude-haiku-4-5-20251001

# Skills
app.skills.enabled=true
app.skills.keywords.software-engineer=python,java,go,rust,...
```

---

## Security Considerations

1. **Password Hashing:** BCryptPasswordEncoder (adaptive, salted)
2. **API Keys:** UUID-based, stored in DB, verified on every request
3. **CSRF Protection:** Enabled for all POST/PATCH/DELETE (except `/api/**`)
4. **XSS Prevention:** Thymeleaf escaping by default, CSP headers
5. **SQL Injection:** Spring Data JPA parameterized queries (no raw SQL)
6. **Rate Limiting:** Per-IP + per-requestKey to prevent brute force
7. **Data Isolation:** All queries scoped to `userId` (no cross-account access)
8. **Secret Management:** Environment variables (ANTHROPIC_API_KEY, DB credentials)

---

## Deployment Checklist

- [ ] Database: PostgreSQL with backup strategy
- [ ] HTTPS: Enforce via HSTS headers + Railway SSL
- [ ] Secrets: Store in environment (not in code)
- [ ] API Keys: Generated securely, revocable per user
- [ ] Logging: Structured logs to stdout for Railway integration
- [ ] Health: `/actuator/health` returns 200 for Railway probes
- [ ] CORS: Configured if needed for separate frontend
- [ ] Rate Limits: Adjust `plusSeconds(2)` for your traffic profile
- [ ] Backups: DB backups on schedule + disaster recovery plan
