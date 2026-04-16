# gitlab-analytics

Internal engineering analytics backend. Connects to a self-hosted GitLab instance, syncs merge request data, and calculates per-developer contribution metrics for a given team and time period.

**Not a performance scoring tool.** Metrics are engineering signals for retrospectives and team self-improvement — not ratings or KPIs.

---

## What it does

- Registers GitLab instances and projects to track
- Syncs MR data (merge requests, commits with diff stats, discussions, approvals) into a local PostgreSQL database
- Calculates contribution metrics per developer over a custom or preset period
- Returns a structured JSON report with raw metrics, normalized values, and team percentile comparisons

---

## Quick start

### Prerequisites

- Java 21
- Docker

### 1. Start PostgreSQL

```bash
docker run -d --name gitlab-analytics-pg \
  -e POSTGRES_DB=gitlab_analytics \
  -e POSTGRES_USER=analytics \
  -e POSTGRES_PASSWORD=analytics \
  -p 5432:5432 \
  postgres:16-alpine
```

### 2. Run the app

```bash
API_TOKEN=your-secret-token \
DB_URL=jdbc:postgresql://localhost:5432/gitlab_analytics \
DB_USERNAME=analytics \
DB_PASSWORD=analytics \
./gradlew bootRun -x test -x checkstyleMain -x pmdMain -x spotbugsMain
```

App starts on `http://localhost:8080`. Swagger UI: `http://localhost:8080/swagger-ui.html`

All API requests require the header: `Authorization: Bearer your-secret-token`

---

## Configuration

| Variable | Default | Description |
|---|---|---|
| `API_TOKEN` | `changeme-in-production` | Static bearer token for all API calls |
| `DB_URL` | `jdbc:postgresql://localhost:5432/gitlab_analytics` | PostgreSQL JDBC URL |
| `DB_USERNAME` | `analytics` | Database username |
| `DB_PASSWORD` | `analytics` | Database password |

---

## Setup guide

### Step 1 — Register a GitLab source

```bash
curl -X POST http://localhost:8080/api/v1/sources/gitlab \
  -H "Authorization: Bearer your-secret-token" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "my-gitlab",
    "baseUrl": "https://git.example.com",
    "token": "glpat-xxxxxxxxxxxx"
  }'
# → { "id": 1, ... }
```

The GitLab token needs `read_api` scope. Test connectivity:

```bash
curl -X POST http://localhost:8080/api/v1/sources/gitlab/1/test \
  -H "Authorization: Bearer your-secret-token"
# → { "status": "ok", "username": "...", "gitlabUserId": 123 }
```

### Step 2 — Register a project

You need the numeric GitLab project ID (visible in project Settings or via
`GET /api/v4/projects/<url-encoded-path>`).

```bash
curl -X POST http://localhost:8080/api/v1/projects \
  -H "Authorization: Bearer your-secret-token" \
  -H "Content-Type: application/json" \
  -d '{
    "gitSourceId": 1,
    "gitlabProjectId": 1538,
    "pathWithNamespace": "group/subgroup/project-name",
    "name": "project-name"
  }'
# → { "id": 1, ... }
```

### Step 3 — Register tracked users

Each team member needs a `TrackedUser` record and at least one `TrackedUserAlias` linking their GitLab account.

```bash
# Create user
curl -X POST http://localhost:8080/api/v1/users \
  -H "Authorization: Bearer your-secret-token" \
  -H "Content-Type: application/json" \
  -d '{"displayName": "Jane Smith", "email": "jane@example.com"}'
# → { "id": 2, ... }

# Add GitLab alias — gitlabUserId is the numeric ID from GitLab
curl -X POST http://localhost:8080/api/v1/users/2/aliases \
  -H "Authorization: Bearer your-secret-token" \
  -H "Content-Type: application/json" \
  -d '{
    "gitlabUserId": 1234,
    "username": "j.smith",
    "name": "Jane Smith",
    "email": "jane@example.com"
  }'
```

> **Important**: The `email` in the alias must match the `author_email` field in git commits.
> This is how commits are attributed to tracked users.
> Verify with: `git log --format='%ae' | sort -u`
> Commit emails and GitLab account emails often differ.

### Step 4 — Run a sync

```bash
curl -X POST http://localhost:8080/api/v1/sync/manual \
  -H "Authorization: Bearer your-secret-token" \
  -H "Content-Type: application/json" \
  -d '{
    "projectIds": [1],
    "dateFrom": "2026-01-01T00:00:00Z",
    "dateTo": "2026-04-16T23:59:59Z",
    "fetchNotes": true,
    "fetchApprovals": true,
    "fetchCommits": true
  }'
# → { "jobId": 1, "status": "STARTED", ... }
```

Check status:

```bash
curl http://localhost:8080/api/v1/sync/jobs/1 \
  -H "Authorization: Bearer your-secret-token"
# → { "status": "COMPLETED", ... }
```

> `fetchCommits: true` makes one extra GitLab API call per commit to fetch diff stats
> (`additions`, `deletions`). For large projects this takes a few extra minutes.
> Commits are not re-fetched on subsequent syncs if already in the database.

### Step 5 — Get the report

```bash
curl -X POST http://localhost:8080/api/v1/reports/contributions \
  -H "Authorization: Bearer your-secret-token" \
  -H "Content-Type: application/json" \
  -d '{
    "projectIds": [1],
    "userIds": [2, 3, 4],
    "periodPreset": "LAST_30_DAYS",
    "groupBy": "USER",
    "reportMode": "MERGED_IN_PERIOD",
    "metrics": null
  }'
```

**`periodPreset`** values: `LAST_7_DAYS`, `LAST_30_DAYS`, `LAST_90_DAYS`, `LAST_180_DAYS`, `CUSTOM`

For `CUSTOM`, add `"dateFrom": "...", "dateTo": "..."` (ISO-8601 with `Z`).

**`reportMode`**:
- `MERGED_IN_PERIOD` — MRs merged within the period (recommended)
- `CREATED_IN_PERIOD` — MRs created within the period

**`metrics`**: array of metric keys to include, or `null` for all.

---

## Metrics reference

### Delivery

| Metric | How it's calculated | How to read |
|---|---|---|
| `mr_opened_count` | Count of MRs authored in the period | Raw output volume |
| `mr_merged_count` | Authored MRs with non-null `merged_at` in the period | Completed deliveries |
| `commits_in_mr_count` | Commits in authored MRs where `author_email` matches the user | Attributed by commit email |
| `active_days_count` | Unique calendar days with a commit, note, or approval | Reflects actual working days |
| `repositories_touched_count` | Distinct tracked projects with authored MRs | Cross-project activity |

### Change volume

| Metric | How it's calculated | How to read |
|---|---|---|
| `lines_added` | Sum of `additions` across the user's own commits | Raw code output |
| `lines_deleted` | Sum of `deletions` across the user's own commits | High deletions = refactoring or cleanup |
| `lines_changed` | `lines_added + lines_deleted` | Total churn |
| `avg_mr_size_lines` | Average of (total additions + deletions per MR across all commits in that MR) | High values = hard-to-review MRs |
| `median_mr_size_lines` | Median of the same — less skewed by outliers | Compare to avg: large gap means a few giant MRs |
| `avg_mr_size_files` | Average `changes_count` per authored MR (from GitLab MR metadata) | |
| `files_changed` | Sum of `files_changed_count` per user commit | Currently 0 if not populated |

### Review contribution

| Metric | How it's calculated | How to read |
|---|---|---|
| `review_comments_written_count` | Non-system notes left in **other people's** MRs | Core review engagement |
| `review_threads_started_count` | Notes that are the earliest in their discussion thread in a foreign MR | Initiating feedback vs replying |
| `mrs_reviewed_count` | Unique foreign MRs with at least one note or approval from the user | Breadth of review |
| `approvals_given_count` | Approvals on foreign MRs | |

### Flow metrics (authored MRs only)

| Metric | How it's calculated | How to read |
|---|---|---|
| `avg_time_to_first_review_minutes` | Avg minutes from MR `created_at` to first external note or approval | Long = MRs sit unreviewed |
| `median_time_to_first_review_minutes` | Median of the same | Preferred for skewed data |
| `avg_time_to_merge_minutes` | Avg minutes from `created_at` to `merged_at` | End-to-end cycle time |
| `median_time_to_merge_minutes` | Median of the same | |
| `rework_mr_count` | Authored MRs with at least one commit by the user after first external review | |
| `rework_ratio` | `rework_mr_count / mr_merged_count` | 0.2 = 20% of MRs had post-review iterations; not inherently bad |
| `self_merge_count` | MRs where `merged_by` gitlab user ID matches the author | |
| `self_merge_ratio` | `self_merge_count / mr_merged_count` | High ratio may indicate bypassed review |

### Normalized

| Metric | How it's calculated | How to read |
|---|---|---|
| `mr_merged_per_active_day` | `mr_merged_count / active_days_count` | Throughput relative to working days |
| `comments_per_reviewed_mr` | `review_comments_written_count / mrs_reviewed_count` | < 1 = mostly approvals; > 2 = substantive feedback |

### Team comparison (`teamComparison` field)

For `mr_merged_count`, `review_comments_written_count`, `approvals_given_count`, and
`mrs_reviewed_count` the response includes a `_percentile` value showing where this user
ranks within the current report's cohort. `100.0` = highest, `0.0` = lowest.

---

## Build

```bash
./gradlew build           # full build + tests + static analysis
./gradlew build -x test   # skip tests
./gradlew test            # tests only (requires Docker for Testcontainers)
./gradlew check -x test   # static analysis only
```
