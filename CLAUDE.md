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

- **`api/controller/`** — REST endpoints, delegate to services
- **`api/dto/`** — request/response DTOs (no JPA entities exposed directly)
- **`api/exception/`** — `GlobalExceptionHandler`, custom exceptions
- **`config/`** — Spring configuration beans
- **`domain/model/`** — JPA entities + enums
- **`domain/repository/`** — Spring Data JPA repositories
- **`gitlab/client/`** — `GitLabApiClient` (WebClient-based, reactive)
- **`gitlab/dto/`** — GitLab API response DTOs
- **`metrics/`** — `MetricCalculationService`
- **`sync/`** — `SyncOrchestrator`, `SyncJobService`
- **`security/`** — `BearerTokenAuthFilter`
- **`encryption/`** — `EncryptionService` interface + `NoOpEncryptionService`

## Key Patterns

**Security**: Static Bearer token via `BearerTokenAuthFilter`. Unauthenticated requests return `401` (configured via `HttpStatusEntryPoint`). Token configured in `app.security.api-token`.

**jsonb columns**: Use `@JdbcTypeCode(SqlTypes.JSON)` on `String` fields mapped to `jsonb` PostgreSQL columns. Without it Hibernate 6 throws a type mismatch error.

**Async sync**: `SyncOrchestrator.orchestrateAsync` runs in a separate thread pool (configured in `AsyncConfig`). Avoid `@Transactional` on `private` methods — Spring AOP cannot proxy self-invocations.

**Encryption**: `NoOpEncryptionService` stores tokens as plaintext. Set `app.encryption.enabled=true` and provide a real `EncryptionService` bean for production.

**Commit stats**: The GitLab MR list and single-MR endpoints do not return `additions`/`deletions` on this GitLab instance. Stats are fetched individually via `GET /repository/commits/:sha` (one call per new commit during sync). `MetricCalculationService` computes `lines_added/deleted` from commit-level stats, not MR-level.

**Commit attribution**: `isUserCommit` matches commits by email. It checks both `TrackedUser.email` and all `TrackedUserAlias.email` values (lowercased). Commit emails often differ from GitLab account emails — always register the correct commit email in the alias.

## Database

PostgreSQL + Flyway. Migrations in `src/main/resources/db/migration/`:

- `V1__initial_schema.sql` — full schema
- `V2__fix_indexes.sql` — index fixes (redundant indexes dropped, partial indexes on nullable FK columns)

Hibernate DDL is set to `validate` — all schema changes must go through Flyway migrations.

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

**`DatabaseStructureTest`** — pg-index-health checks using `pg-index-health-test-starter:0.20.3` (last version compatible with Spring Boot 3.3.x). Runs only static checks; skips `flyway_schema_history` via `SkipFlywayTablesPredicate.ofDefault()`.

## Key Configuration

`src/main/resources/application.yml` — central config.
`src/test/resources/application-test.yml` — test overrides.

Environment variables:

- `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` — database connection
- `API_TOKEN` — bearer token for API authentication

## Dependencies

Spring Boot 3.3.4, Java 21, Gradle Kotlin DSL.

Notable non-BOM dependencies (pin versions manually):

- `springdoc-openapi-starter-webmvc-ui:2.6.0`
- `pg-index-health-test-starter:0.20.3`
- `spotbugs-annotations:4.8.6`
