# Product Requirements Document: Job Application Tracker

**Version:** 1.0
**Date:** 2026-03-22
**Status:** Living document — update as features ship

---

## 1. Problem Statement

Job seekers applying to many roles simultaneously lose track of where they stand, forget to follow up, and have no signal on whether their resume and strategy are working. Existing tools (spreadsheets, Notion, LinkedIn saved jobs) are passive — they record what happened but don't help you act on it.

The problem has three layers:

1. **Organisation** — Applications get lost. Status becomes stale. You forget which company you're waiting on.
2. **Follow-through** — Nobody reminds you to follow up after two weeks of silence.
3. **Signal** — You don't know if your resume is competitive or whether the market has shifted.

---

## 2. Target Users

**Primary: Active job seeker (solo)**
- Applying to 5–50+ roles over a 1–6 month search
- Mix of LinkedIn, company sites, and referrals
- May be a developer (comfortable with API keys) or non-technical
- Wants structure without spreadsheet overhead

**Secondary: Power users / developers**
- Want to submit applications programmatically from scripts or browser extensions
- Need a reliable REST API with idempotency guarantees
- May build integrations (e.g., LinkedIn scraper, auto-apply bot)

---

## 3. Goals

### Product Goals
| Goal | How We Measure It |
|------|--------------------|
| Users track every application with <30 seconds effort per entry | Form submit time P90 < 5s; AI auto-fill reduces time by 70% |
| No application goes more than 5 days without a status check | Stale app alert opens rate > 60% |
| Users get a resume feedback loop without hiring a coach | HR Lens analysis viewed per user > 1 per job search |
| API-first design enables integrations | REST API usage from non-browser clients |

### Technical Goals
- 99.9% uptime on Railway with graceful restarts
- Zero data loss (PostgreSQL with nightly backups)
- < 2 second P95 response time for all web pages
- All user data isolated (no cross-account data leaks)

---

## 4. Feature Requirements

### 4.1 Job Application Tracking (Core — DONE)

**Users can:**
- Submit an application with: company name, position title, date applied, status, source, notes
- View all their applications in a sortable dashboard table
- Change status (APPLIED → INTERVIEWING → OFFER / REJECTED) inline from the table
- View full application detail on a dedicated page
- Delete an application

**Status values:** `APPLIED`, `INTERVIEWING`, `OFFER`, `REJECTED`

**Behaviour:**
- Applications are scoped to the authenticated user — no cross-user access
- `requestKey` enables idempotent API submission (safe retries, no duplicates)
- API key authentication allows programmatic submission without a session

**Acceptance criteria:**
- A user who submits the same application twice (same requestKey, same content) sees one record
- A user cannot access, modify, or delete another user's applications
- Status changes are reflected immediately without page reload

---

### 4.2 User Accounts & Authentication (Core — DONE)

**Users can:**
- Register with email + password (minimum 8 characters)
- Receive an email verification link (30-minute expiry)
- Log in after verification
- Log out
- Generate API keys from their account settings
- Deactivate API keys

**Behaviour:**
- Passwords hashed with BCrypt; tokens stored as SHA-256 hashes
- Form login for web UI; API key (`X-API-Key` header) for REST
- Unverified accounts cannot log in; a resend-verification flow is provided

---

### 4.3 AI Resume Parsing (Core — DONE)

**Users can:**
- Upload a PDF or DOCX resume on the Add Application form
- Have the form auto-filled with: current title, target title, top skills, notes

**Behaviour:**
- Calls Claude Haiku with up to 8000 characters of resume text
- Returns structured JSON: `currentTitle`, `targetTitle`, `summary`, `topSkills`, `notes`
- Auto-fills the application form fields; user can edit before submitting
- Gracefully degrades if Anthropic API key is not configured

---

### 4.4 HR Lens Resume Analysis (Core — DONE)

**Users can:**
- Upload their resume once for a persistent AI critique
- View a structured analysis on their dashboard with: pros, cons, improvement actions, conclusion

**Behaviour:**
- Uses Claude Haiku with the user's resume text + their last 25 applications + current top 8 in-demand skills
- Analysis is "brutally honest" — not generic career advice
- Analysis JSON is stored in `user_resumes` table and shown on every dashboard load
- One resume per user; uploading again overwrites the previous

**Output format:**
- `pros[]` — 4–6 resume strengths
- `cons[]` — 4–6 weaknesses relative to the market
- `improvements[]` — 5 specific, actionable steps (each with title + description)
- `conclusion` — one-paragraph summary verdict

---

### 4.5 Follow-Up Draft Generation (Core — DONE)

**Users can:**
- See a "Stale Applications" alert on their dashboard for any application with no status update for 5+ days (configurable)
- Click "Generate follow-up draft" on any stale APPLIED or INTERVIEWING application
- See a Claude-generated <120-word professional follow-up email (subject + body)

**Behaviour:**
- Draft is stored on the application record (`followUpDraft`, `followUpDraftGeneratedAt`)
- Context-aware: different tone for APPLIED vs INTERVIEWING
- Requires Anthropic API key; gracefully fails with error message if absent

---

### 4.6 Job Market Intelligence (Core — DONE, data quality TBD)

**Users see on their dashboard:**
- A 30-day SVG polyline chart of total software job postings (Adzuna API)
- A market health label: `Healthy` (+10% growth), `OK` (stable), `Poor` (−10% decline)
- Top 8 in-demand skills for three roles: Software Engineer, Data Engineer, AI Engineer
- Sample job count per skills analysis
- Last updated timestamp + error indicator

**Behaviour:**
- Polls Adzuna API every 15 minutes (startup + scheduled)
- Skills extracted by matching 100+ keywords + aliases against job description HTML
- Gracefully degrades: dashboard loads normally if Adzuna is unavailable
- Requires `ADZUNA_APP_ID` + `ADZUNA_APP_KEY` environment variables

---

### 4.7 Hacker News Feed (Sidebar — DONE)

**Users see:**
- Top 5 Hacker News stories on their dashboard, updated every 5 minutes

**Behaviour:**
- Fetches via HN Firebase API (no credentials required)
- Shows title, author, score; title links to original story
- Gracefully degrades if HN API is unavailable

---

### 4.8 Application Metrics Endpoint (Internal — DONE)

`GET /api/metrics` returns:
- `created` — total applications created in this process lifetime
- `replayed` — idempotent replay count
- `rateLimited` — rejected duplicate submissions
- `deadLettered` — caught exceptions
- `appRuntimeSeconds` — process uptime

*These are in-memory counters; they reset on restart. Separate from Spring Boot Actuator.*

---

## 5. Planned Features (Roadmap)

These are not yet built. Ordered by priority.

### P1: Search, Filter & Pagination
- Free-text search by company name or position
- Filter by status (multi-select dropdown)
- Date range filter
- Server-side pagination (20 per page)
- Sort by date applied, company name, or status

**Why:** Dashboard becomes unusable after 50+ applications.

---

### P2: Activity Timeline
- Track every status change and note edit with who/what/when
- Show timeline on the application detail page
- New table: `application_events(id, application_id, event_type, old_value, new_value, occurred_at)`

**Why:** Users need an audit trail when preparing for interviews ("last contacted 2 weeks ago").

---

### P3: Analytics Dashboard
- Cards: total applied, in interview, offer rate, average days-to-response
- Source breakdown: which channel produces the most interviews
- Weekly trend chart: applications submitted per week
- Heatmap of most active days

**Why:** Users need to know if their strategy is working.

---

### P4: Data Export
- CSV export with filters (date range, status)
- JSON export for backup
- Accessible from the dashboard toolbar

**Why:** Users want their data in case they leave the product, and recruiters ask for summaries.

---

### P5: API Key Audit Logs
- Record each API request: key ID, endpoint, timestamp, client IP, response status
- Viewable in account settings (last 100 requests)

**Why:** Users building integrations need to debug their scripts.

---

### P6: Duplicate Detection
- Warn when submitting a new application for the same company + position within a configurable window (default: 30 days)
- Allow explicit override with confirmation

**Why:** Power users with auto-submit scripts create duplicates.

---

### Backlog (Unscheduled)
- Calendar integration (link interviews to Google Calendar / Outlook)
- Resume version history (track which resume version was used per application)
- Interview prep checklist per application
- Chrome extension: one-click capture from LinkedIn job listings
- Browser push notifications for overdue follow-ups

---

## 6. Non-Functional Requirements

### Security
- All user data scoped by `userId`; enforced in the service layer on every query
- Passwords never stored in plain text (BCrypt)
- Verification tokens stored only as SHA-256 hashes
- HTTPS enforced in production (Railway handles TLS termination; HSTS set)
- Security headers on all responses: `X-Content-Type-Options`, `X-Frame-Options`, HSTS, CSP
- CSRF protection for all state-changing form endpoints
- No secrets hardcoded in source (all via environment variables)

### Performance
- Dashboard page load < 2 seconds P95 (all data fetched synchronously from DB + cache)
- API endpoints < 200ms P95 for CRUD operations
- Resume parsing < 15 seconds (Claude Haiku latency + PDF extraction)
- Skills analytics pre-computed and cached; dashboard never blocks on Adzuna API

### Reliability
- Graceful shutdown: in-flight requests complete within 30 seconds before process exit
- Dead-letter queue captures failed submissions for investigation
- External API failures (Adzuna, Claude, HN) never crash the application; dashboard degrades gracefully
- Schema changes managed exclusively via Flyway migrations (Hibernate set to `validate`)

### Data Integrity
- All writes use `@Transactional` boundaries
- Idempotency key prevents duplicate application records on retry
- Rate limiting (2-second window per IP + requestKey) prevents hammering

### Scalability
- Single-instance vertical scaling sufficient for current user base
- Application is stateless (session in DB); horizontal scale possible with sticky sessions or JDBC session store
- No in-memory state that cannot be reconstructed on restart (market data re-polled; metrics reset gracefully)

---

## 7. Out of Scope (This Version)

- Mobile app (web is responsive; good enough for MVP)
- Multi-user teams / organisations
- Integration with ATS systems (Greenhouse, Lever, Workday)
- Job recommendation engine
- Video interview prep
- Payment / subscriptions (free-only product currently)

---

## 8. Assumptions & Constraints

| Assumption | Implication |
|------------|-------------|
| Single developer maintaining the project | Feature pace is slow; prefer conservative architecture |
| Deployed on Railway (single-region) | No multi-AZ failover; PostgreSQL backup is manual |
| Users are primarily English-speaking tech workers | Skill dictionary tuned for US tech job market |
| Adzuna API free tier has rate limits | 15-minute polling interval to stay under limits |
| Claude Haiku is cost-effective for user volume | ~$0.001 per resume parse; acceptable at current scale |

---

## 9. Glossary

| Term | Definition |
|------|------------|
| `requestKey` | Idempotency key for API submissions; auto-generated from company+position+date if not provided |
| Legacy owner | A system account (`legacy-api@jobtracker.local`) used as the owner for all API-key authenticated submissions |
| Dead-letter event | A record of a failed submission stored for audit / debugging |
| HR Lens | The AI resume analysis feature that provides a structured critique relative to current market demand |
| Stale application | An application with `updatedAt` more than `app.followup.stale-days` (default 5) days ago and status APPLIED or INTERVIEWING |
| Market snapshot | A point-in-time record of total job postings matching a search query on Adzuna |
| Skill demand snapshot | A point-in-time record of how often a skill appears in job descriptions for a given role |
