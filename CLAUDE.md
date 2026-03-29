# Job Application Tracker — Claude Code Configuration

## Project Overview

Spring Boot 3.2 / Java 17 application for tracking job applications, backed by a JPA/Hibernate data layer.

## Build & Test

```bash
./gradlew build          # compile + test
./gradlew test           # run all tests
./gradlew compileJava    # compile only
```

## After Every Code Change Session

**Always run the full test suite before ending a session:**

```bash
./gradlew test
```

All tests must pass before the session is considered complete. If any test fails, investigate and fix the failure — do not leave the codebase in a broken state.

## Code Style

- Follow existing patterns in the codebase (constructor injection, explicit imports, no wildcards)
- Keep files under 800 lines; prefer small focused classes
- No hardcoded secrets or API keys
- Immutable patterns preferred — return new objects rather than mutating existing ones
- Ensure that no API keys are exposed to any external users

## Architecture

- **Controllers** (`controller/`) — HTTP layer, request/response mapping
- **Services** (`service/`) — business logic
- **Entities** (`entity/`) — JPA-managed database models
- **Repositories** (`repository/`) — Spring Data JPA interfaces
- **Models** (`model/`) — DTOs and request/response objects
- **Config** (`config/`) — Spring configuration beans
- **Security** (`security/`) — auth filters and handlers
