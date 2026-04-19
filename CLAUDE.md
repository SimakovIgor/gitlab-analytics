# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# Full build with all checks and tests
./gradlew build

# Build without tests
./gradlew build -x test

# Run all tests
./gradlew test

# Run static analysis only (Checkstyle + PMD + SpotBugs)
./gradlew check -x test

# Run a single test class
./gradlew test --tests "io.simakov.analytics.api.controller.SyncControllerTest"

# Clean build (use when Flyway migration conflicts appear in tests)
./gradlew clean build
```

## Code Quality

Static analysis runs automatically during build:

- **Checkstyle 10.17.0**: `config/checkstyle/checkstyle.xml`
- **PMD 7.4.0**: `config/pmd/pmd-rules.xml`
- **SpotBugs 4.8.6**: `config/spotbugs/spotbugs-exclude.xml`

### Key Checkstyle Rules

- `LineLength max=199` (imports and URLs are excluded)
- `NeedBraces` — braces required for all control flow (`if`, `for`, `while`, etc.)
- `OperatorWrap option=NL` — binary operators go on the **next** line when wrapping
- `SeparatorWrap DOT option=nl` — `.` in method chains goes on the next line
- `AvoidStarImport` — explicit imports only
- `CustomImportOrder`: `THIRD_PARTY_PACKAGE ### STANDARD_JAVA_PACKAGE ### STATIC` (alphabetically within groups, blank line between groups)
- `IllegalCatch` — suppress with `@SuppressWarnings("checkstyle:IllegalCatch")` where needed
- `MethodLength max=100`, `JavaNCSS methodMaximum=60`, `CyclomaticComplexity max=15`
- Banned imports: `javax.transaction.Transactional`, JUnit 4, Hamcrest, `org.junit.jupiter.api.Assertions.*`
- Allowed abbreviations: `SSD, ID, DTO, API, MR, IT, DB, URL, UTC`

Use `@SuppressWarnings("checkstyle:RuleName")` for annotation-based suppression (enabled via `SuppressWarningsFilter`).

## Architecture

Layered package structure (no ArchUnit enforcement):

```
Controller → Service → Repository → Model
```

- **`api/controller/`** — REST endpoints (`/api/v1/**`), delegate to services
- **`api/dto/`** — request/response DTOs (no JPA entities exposed directly)
- **`api/exception/`** — `GlobalExceptionHandler`, custom exceptions
- **`config/`** — Spring configuration beans
- **`domain/model/`** — JPA entities + enums
- **`domain/repository/`** — Spring Data JPA repositories
- **`gitlab/client/`** — `GitLabApiClient` (WebClient-based, reactive)
- **`gitlab/dto/`** — GitLab API response DTOs
- **`metrics/`** — `MetricCalculationService`, `Metric` enum
- **`snapshot/`** — `SnapshotService` (daily scheduler + manual API), `SnapshotHistoryService`
- **`sync/`** — `SyncOrchestrator`, `SyncJobService`
- **`web/controller/`** — Thymeleaf web UI controllers (`WebController`, `HistoryController`, `SettingsController`)
- **`web/`** — `ContributorDiscoveryService`, `UserAliasService`, `ReportViewService`
- **`web/dto/`** — `ReportPageData`, `MrSummaryDto`, `HistoryPageData`, `SettingsPageData`, etc.
- **`security/`** — `BearerTokenAuthFilter`
- **`encryption/`** — `EncryptionService` interface + `NoOpEncryptionService`

Templates: `src/main/resources/templates/` (login, report, history, settings)
Static assets: `src/main/resources/static/css/analytics.css`

## Key Patterns

**Two Security filter chains** (order matters):

- `@Order(1)` — API chain: `securityMatcher("/api/**")`, stateless, Bearer token, 401 on failure
- `@Order(2)` — Web chain: OAuth2 login (GitHub), session-based, redirects to `/login`

**Metric enum** (`metrics/model/Metric.java`) — single source of truth for all metrics. Every metric has `key()` (JSON/DB key), `label()` (Russian UI label), `description()` (Russian legend text),
`category()`, `isInMinutes()`, `isChartVisible()`. Use `Metric.XXX.key()` everywhere instead of hardcoded strings. `Metric.chartOptions()` builds the history dropdown; `Metric.minuteKeys()` returns
metrics stored in minutes.

**PeriodType enum** — has `toDays()` method. Use `PeriodType.valueOf(str).toDays()` instead of switch statements. Values: `LAST_7_DAYS(7)`, `LAST_30_DAYS(30)`, `LAST_90_DAYS(90)`,
`LAST_180_DAYS(180)`, `LAST_360_DAYS(360)`, `CUSTOM(0)`.

**jsonb columns**: Use `@JdbcTypeCode(SqlTypes.JSON)` on `String` fields mapped to `jsonb` PostgreSQL columns. Without it Hibernate 6 throws a type mismatch error.

**Async sync**: `SyncOrchestrator.orchestrateAsync` runs in a separate thread pool (configured in `AsyncConfig`). Avoid `@Transactional` on `private` methods — Spring AOP cannot proxy
self-invocations.

**Encryption**: `NoOpEncryptionService` stores tokens as plaintext. Set `app.encryption.enabled=true` and provide a real `EncryptionService` bean for production.

**Commit stats**: The GitLab MR list and single-MR endpoints do not return `additions`/`deletions` on this GitLab instance. Stats are fetched individually via `GET /repository/commits/:sha` (one call
per new commit during sync). `MetricCalculationService` computes `lines_added/deleted` from commit-level stats, not MR-level.

**GitLab API timeout**: `app.gitlab.read-timeout-seconds` (default 30s). Increase to 120s+ for slow GitLab instances. A full sync with commits for 450+ MRs takes ~3-4 minutes with parallel
processing (`mrProcessingExecutor` thread pool).

**Commit attribution**: `isUserCommit` matches commits by email. It checks both `TrackedUser.email` and all `TrackedUserAlias.email` values (lowercased). Commit emails often differ from GitLab
account emails — always register the correct commit email in the alias.

**MR attribution**: Done by `author_gitlab_user_id` (stored on each `MergeRequest`). When a `TrackedUser` is added, `UserAliasService.saveAlias()` calls `GitLabApiClient.findUserIdByUsername()` using
the username-prefix of the email (e.g., `a.upatov` from `a.upatov@uzum.com`) to resolve and store the `gitlab_user_id` in `tracked_user_alias`. If the email prefix doesn't match the GitLab username,
`gitlab_user_id` will be null and MR attribution will fail for that user.

**`ManualSyncRequest` field names**: `fetchNotes`, `fetchApprovals`, `fetchCommits` (not `syncNotes`/`syncCommits`). All default to `false` — must be explicitly set to `true` for a full sync. The
`SettingsController.triggerBackfill()` sets all three to `true`.

**Snapshot backfill**: Snapshots are created daily by scheduler (`app.snapshot.cron`, default `0 0 2 * * *`). Two endpoints for manual control:

- `POST /api/v1/snapshots/backfill?days=360` — creates weekly snapshots going back N days (step=7d). Auto-triggered from the UI when users are added during onboarding. This is the main backfill for
  History chart.
- `POST /api/v1/snapshots/run` — creates a single snapshot for a specific `snapshotDate` and `windowDays`.
  The History chart reads from `metric_snapshot` table — if data looks missing, check whether snapshots exist for the needed date range.

**Thymeleaf + Chart.js**: History chart data is passed as a JSON string via model attribute `chartData`, injected into JS with `th:inline="javascript"` + `JSON.parse([[${chartData}]])`. Period filter
on History page uses days (7/30/90/180/360), on Report page uses `PeriodType` string values.

**MR drill-down**: `GET /report/user/{id}/mrs?period=&projectIds=` — returns `List<MrSummaryDto>` (JSON). Resolves `gitlabUserId` via `TrackedUserAlias`, queries
`MergeRequestRepository.findMergedInPeriodByAuthors()`. Called from report.html JS on row click, renders in modal.

**Report row click**: clicking a row opens a modal with the user's merged MRs for the current period/filters. The chart is NOT affected by row clicks (chart is controlled only by the metric selector
and period buttons).

## Database

PostgreSQL + Flyway. Migrations in `src/main/resources/db/migration/`:

- `V1__initial_schema.sql` — full schema
- `V2__fix_indexes.sql` — index fixes (redundant indexes dropped, partial indexes on nullable FK columns)
- `V3__add_snapshot_fields.sql` — adds `window_days`, `date_from`, `date_to`, `scope_type` to `metric_snapshot`
- `V4__drop_report_mode.sql` — removes `report_mode` column (always MERGED_IN_PERIOD now)
- `V5__cascade_deletes.sql` — cascade deletes on FK constraints

Hibernate DDL is set to `validate` — all schema changes must go through Flyway migrations.

**Flyway conflict warning**: If tests fail with "Found more than one migration with version N", run `./gradlew clean` to clear stale build artifacts, then rebuild.

### Index Conventions

| Type              | Pattern                    |
|-------------------|----------------------------|
| Index             | `idx_<table>_<column>`     |
| Unique constraint | `uq_<table>_<col1>_<col2>` |
| Foreign key       | `fk_<table>_<column>`      |

Partial indexes (`WHERE col IS NOT NULL`) are required for nullable columns to avoid flagging by pg-index-health.

## Testing

All integration tests extend `BaseIT`:

- `@SpringBootTest(webEnvironment = RANDOM_PORT)`
- `@ActiveProfiles("test")`
- Testcontainers `PostgreSQLContainer` with `@ServiceConnection`
- `TestRestTemplate` for HTTP calls
- `@MockBean GitLabApiClient` — GitLab is always mocked in IT
- `@AfterEach cleanUpDatabase()` — truncates all tables; add new tables here when creating migrations

Test naming: `*Test` (not `*IT`), even for integration tests.

**`DatabaseStructureTest`** — pg-index-health checks using `pg-index-health-test-starter:0.20.3` (last version compatible with Spring Boot 3.3.x). Runs only static checks; skips
`flyway_schema_history` via `SkipFlywayTablesPredicate.ofDefault()`.

**OAuth2 in tests**: `application-test.yml` provides fake GitHub credentials (`test-github-client-id` / `test-github-client-secret`) so the app context starts without real OAuth2 config.

## Key Configuration

`src/main/resources/application.yml` — central config.
`src/test/resources/application-test.yml` — test overrides.

Environment variables:

- `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` — database connection
- `API_TOKEN` — bearer token for REST API authentication (default: `changeme-in-production`)
- `GITHUB_CLIENT_ID`, `GITHUB_CLIENT_SECRET` — GitHub OAuth2 app credentials for web UI login

## Observability

Spring Boot Actuator + Micrometer are configured. Exposed endpoints:

- `GET /actuator/health` — full health (DB, disk, liveness, readiness). `show-details: always`.
- `GET /actuator/health/liveness` — liveness probe
- `GET /actuator/health/readiness` — readiness probe
- `GET /actuator/metrics` — Micrometer metric list
- `GET /actuator/prometheus` — Prometheus scrape endpoint

All metrics carry the tag `application=gitlab-analytics` (set via `management.metrics.tags.application`). Enabled metric groups: JVM, process, system, HTTP server requests, HikariCP.

The `/actuator/**` endpoints are exposed over HTTP without additional auth (appropriate for internal/local use). For production, add `management.endpoints.web.base-path` behind an internal network or
add security.

## Dependencies

Spring Boot 3.3.4, Java 21, Gradle Kotlin DSL.

Notable non-BOM dependencies (pin versions manually):

- `spring-boot-starter-oauth2-client` — GitHub OAuth2 login for web UI
- `spring-boot-starter-thymeleaf` + `thymeleaf-extras-springsecurity6` — server-side templates
- `micrometer-registry-prometheus` — Prometheus metrics export (`runtimeOnly`)
- `springdoc-openapi-starter-webmvc-ui:2.6.0`
- `pg-index-health-test-starter:0.20.3`
- `spotbugs-annotations:4.8.6`
