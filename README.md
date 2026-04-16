# GitLab Engineering Analytics

Internal backend service for analyzing developer activity in GitLab repositories.
Provides engineering process insights — not performance scores.

## Quick Start

### Prerequisites

- Java 21
- Docker & Docker Compose

### Run with Docker Compose

```bash
# Build the JAR first
./gradlew bootJar

# Start the stack (app + PostgreSQL)
API_TOKEN=your-secret-token docker-compose up --build
```

The API is available at `http://localhost:8080`.
Swagger UI: `http://localhost:8080/swagger-ui.html`

### Run locally (against Docker PostgreSQL)

```bash
# Start only the database
docker-compose up postgres -d

# Run the application
./gradlew bootRun --args='--spring.profiles.active=local'
```

## Configuration

| Env Variable  | Default                                             | Description                      |
|---------------|-----------------------------------------------------|----------------------------------|
| `DB_URL`      | `jdbc:postgresql://localhost:5432/gitlab_analytics` | PostgreSQL JDBC URL              |
| `DB_USERNAME` | `analytics`                                         | Database username                |
| `DB_PASSWORD` | `analytics`                                         | Database password                |
| `API_TOKEN`   | `changeme-in-production`                            | Static bearer token for API auth |

All API requests must include `Authorization: Bearer <API_TOKEN>`.

## Typical Usage Flow

### 1. Register a GitLab source

```bash
curl -X POST http://localhost:8080/api/v1/sources/gitlab \
  -H "Authorization: Bearer your-token" \
  -H "Content-Type: application/json" \
  -d '{"name":"Our GitLab","baseUrl":"https://git.example.com","token":"glpat-xxx"}'
```

### 2. Test connectivity

```bash
curl -X POST http://localhost:8080/api/v1/sources/gitlab/1/test \
  -H "Authorization: Bearer your-token"
```

### 3. Register tracked projects

```bash
curl -X POST http://localhost:8080/api/v1/projects \
  -H "Authorization: Bearer your-token" \
  -H "Content-Type: application/json" \
  -d '{"gitSourceId":1,"gitlabProjectId":123,"pathWithNamespace":"team/backend","name":"backend"}'
```

### 4. Register tracked users with aliases

```bash
# Create user
curl -X POST http://localhost:8080/api/v1/users \
  -H "Authorization: Bearer your-token" \
  -H "Content-Type: application/json" \
  -d '{"displayName":"Alice","email":"alice@example.com"}'

# Add GitLab alias (gitlabUserId from GitLab user profile)
curl -X POST http://localhost:8080/api/v1/users/1/aliases \
  -H "Authorization: Bearer your-token" \
  -H "Content-Type: application/json" \
  -d '{"gitlabUserId":456,"username":"alice.dev","email":"alice@example.com","name":"Alice Dev"}'
```

### 5. Run a manual sync

```bash
curl -X POST http://localhost:8080/api/v1/sync/manual \
  -H "Authorization: Bearer your-token" \
  -H "Content-Type: application/json" \
  -d '{
    "projectIds": [1, 2],
    "dateFrom": "2024-01-01T00:00:00Z",
    "dateTo": "2024-01-31T23:59:59Z",
    "fetchNotes": true,
    "fetchApprovals": true,
    "fetchCommits": true
  }'
# Returns: {"jobId": 1, "status": "STARTED", ...}
```

### 6. Poll sync status

```bash
curl http://localhost:8080/api/v1/sync/jobs/1 \
  -H "Authorization: Bearer your-token"
# Returns: {"jobId":1,"status":"COMPLETED",...}
```

### 7. Get contribution report

```bash
curl -X POST http://localhost:8080/api/v1/reports/contributions \
  -H "Authorization: Bearer your-token" \
  -H "Content-Type: application/json" \
  -d '{
    "projectIds": [1, 2],
    "userIds": [1, 2, 3],
    "periodPreset": "LAST_30_DAYS",
    "groupBy": "USER",
    "reportMode": "CREATED_IN_PERIOD",
    "metrics": ["mr_merged_count","review_comments_written_count","avg_time_to_first_review_minutes"]
  }'
```

## Available Metrics

### Delivery

| Metric                       | Description                      |
|------------------------------|----------------------------------|
| `mr_opened_count`            | MRs created by user in period    |
| `mr_merged_count`            | MRs merged in period             |
| `active_days_count`          | Unique days with any event       |
| `repositories_touched_count` | Distinct repos with authored MRs |
| `commits_in_mr_count`        | Commits in authored MRs          |

### Change Volume

| Metric                 | Description                     |
|------------------------|---------------------------------|
| `lines_added`          | Total additions in authored MRs |
| `lines_deleted`        | Total deletions in authored MRs |
| `avg_mr_size_lines`    | Average MR size (add + del)     |
| `median_mr_size_lines` | Median MR size                  |

### Review Contribution

| Metric                          | Description                          |
|---------------------------------|--------------------------------------|
| `review_comments_written_count` | Non-system notes left in others' MRs |
| `mrs_reviewed_count`            | Unique foreign MRs reviewed          |
| `approvals_given_count`         | Approvals given to others' MRs       |
| `review_threads_started_count`  | Discussions initiated                |

### Flow

| Metric                                | Description                                                 |
|---------------------------------------|-------------------------------------------------------------|
| `avg_time_to_first_review_minutes`    | Avg minutes from MR creation to first external review event |
| `median_time_to_first_review_minutes` | Median of above                                             |
| `avg_time_to_merge_minutes`           | Avg minutes from creation to merge                          |
| `rework_mr_count`                     | MRs with author commits after first review                  |
| `rework_ratio`                        | rework_mr_count / mr_merged_count                           |
| `self_merge_count`                    | MRs merged by the author themselves                         |
| `self_merge_ratio`                    | self_merge_count / mr_merged_count                          |

### Normalized

| Metric                     | Description                     |
|----------------------------|---------------------------------|
| `mr_merged_per_active_day` | Throughput relative to activity |
| `comments_per_reviewed_mr` | Review depth                    |

## Development

```bash
# Run tests (requires Docker for Testcontainers)
./gradlew test

# Run with hot reload
./gradlew bootRun

# Build JAR
./gradlew bootJar
```

## Architecture Notes

```
api/           REST controllers + request/response DTOs
sync/          Async sync orchestration + job lifecycle
metrics/       On-the-fly metric calculation
gitlab/        GitLab API client + DTOs + mapper
domain/        JPA entities + Spring Data repositories
encryption/    Token encryption abstraction (replace NoOp with Vault/KMS)
config/        Spring configuration (security, webclient, openapi, async)
```

**Security**: Replace `NoOpEncryptionService` with a Vault/KMS-backed implementation before production.
The static API token should come from a secrets manager, not application.yml.
# gitlab-analytics
