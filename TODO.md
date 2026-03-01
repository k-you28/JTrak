# Job Application Tracker - Execution Roadmap

This file is your build order. Do not skip phases.

## Rules You Follow

- Finish one phase before starting the next.
- Every feature includes: code, tests, and README update.
- Open a PR for each feature (small PRs, not giant dumps).
- If a feature is not testable, it is not done.

## Phase 0 - Baseline and Cleanup (Do this first)

### 0.1 Project hygiene
- [ ] Add a `CONTRIBUTING.md` with local run + test commands.
- [ ] Add `.editorconfig` for consistent formatting.
- [ ] Add task templates in this file for future issues.

Definition of done:
- New contributor can run app and tests in under 10 minutes.

### 0.2 Test confidence
- [ ] Raise integration coverage around create/update/delete edge cases.
- [ ] Add tests for validation failures (missing company, invalid date, etc.).
- [ ] Add tests for dead-letter behavior on forced failures.

Definition of done:
- `./gradlew test` passes and covers happy path + key failure paths.

## Phase 1 - Core UX Improvements

### 1.1 Search, filter, and sort
- [ ] Add search by company and position.
- [ ] Add status/date filters in UI list page.
- [ ] Add server-side pagination for large lists.

Definition of done:
- User can quickly find any application in under 10 seconds.

### 1.2 Better status workflow
- [ ] Add status transition controls in UI (APPLIED -> INTERVIEWING -> OFFER/REJECTED).
- [ ] Add `lastUpdatedAt` display in list + detail views.
- [ ] Add validation for invalid transitions (optional but good practice).

Definition of done:
- Updating status is one click and clearly visible in UI.

### 1.3 Notes quality
- [ ] Support richer notes formatting (line breaks preserved).
- [ ] Show note preview in list; full note in detail page.
- [ ] Add max-length validation with clear error message.

Definition of done:
- Notes are readable and safe from accidental overflow.

## Phase 2 - High-Value Feature Set

### 2.1 Follow-up reminder system (your original idea)
- [ ] Add `nextFollowUpDate` field to application entity.
- [ ] Add scheduled job that finds overdue follow-ups daily.
- [ ] Add email notification for overdue follow-ups.
- [ ] Add UI badge for "Follow-up due".

Definition of done:
- Overdue applications are automatically surfaced and notified daily.

### 2.2 Activity timeline
- [ ] Track status changes and note edits in an `ApplicationEvent` table.
- [ ] Show timeline on detail page.
- [ ] Include who/what/when in each event.

Definition of done:
- You can audit every important change to an application.

### 2.3 Duplicate detection
- [ ] Warn when adding same company + position within recent time window.
- [ ] Allow override with explicit confirmation.
- [ ] Test duplicate warning and override flow.

Definition of done:
- Users avoid accidental duplicate submissions.

## Phase 3 - Authentication and Accounts

### 3.1 Web UI auth (minimum viable)
- [ ] Add login/logout for UI users.
- [ ] Restrict application records to current user.
- [ ] Add registration flow (or admin-created users only).

Definition of done:
- One user cannot view/edit another user's data.

### 3.2 API security hardening
- [ ] Keep API key auth for service clients.
- [ ] Associate API keys with user/owner.
- [ ] Add API key usage audit logs.

Definition of done:
- You can identify which key made each request.

## Phase 4 - Reporting and Export

### 4.1 Job search analytics dashboard
- [ ] Add simple metrics cards: total applied, interviewing, offer rate, rejection rate.
- [ ] Add weekly trend chart of applications submitted.
- [ ] Add source breakdown (LinkedIn/company/referral/etc.).

Definition of done:
- User can measure whether their search strategy is improving.

### 4.2 Data export
- [ ] CSV export with filter support.
- [ ] Optional JSON export for backup.
- [ ] Include tests for export format and headers.

Definition of done:
- User can download and analyze application history externally.

## Phase 5 - Reliability and Deployment

### 5.1 Production-readiness
- [ ] Add PostgreSQL profile and migration tooling (Flyway/Liquibase).
- [ ] Add health/readiness checks.
- [ ] Add environment-based config validation.

Definition of done:
- App can run beyond local/dev with predictable startup and schema control.

### 5.2 Observability and alerting
- [ ] Add structured logging for request + error context.
- [ ] Add metrics for follow-up job success/failure.
- [ ] Define alerts for repeated failures or high error rates.

Definition of done:
- Operational issues can be detected early and diagnosed quickly.

## Ideas Backlog (Not Started, Not Prioritized)

- Browser notifications for follow-up reminders.
- Calendar integration (Google Calendar / Outlook) for interviews.
- Resume + cover letter version tracking by application.
- Interview prep checklist per application.
- Chrome extension to capture jobs from LinkedIn into tracker.
- AI-generated follow-up email draft based on notes.

## Bug List

- [ ] Add bug here with: summary, steps to reproduce, expected vs actual.
