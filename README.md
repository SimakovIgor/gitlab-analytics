# gitlab-analytics

Internal engineering analytics tool. Connects to a self-hosted GitLab instance, syncs merge request data, calculates per-developer contribution metrics, and visualises them in a web UI.

**Не инструмент оценки.** Метрики — это инженерные сигналы для ретроспектив и командного самоанализа, не рейтинги и не KPI.

---

## What's inside

| Page | URL | Description |
|---|---|---|
| Login | `/login` | GitHub OAuth2 sign-in |
| Dashboard | `/dashboard` | Team overview — tracked users and projects |
| Report | `/report` | Metrics table per person, period filter, delta vs previous period |
| History | `/history` | Line chart — team dynamics over time, any metric |
| Swagger UI | `/swagger-ui.html` | REST API docs |

---

## Quick start

### Prerequisites

- Java 21
- Docker
- GitHub OAuth App ([create one](https://github.com/settings/applications/new), callback URL: `http://localhost:8080/login/oauth2/code/github`)

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
GITHUB_CLIENT_ID=your-github-client-id \
GITHUB_CLIENT_SECRET=your-github-client-secret \
DB_URL=jdbc:postgresql://localhost:5432/gitlab_analytics \
DB_USERNAME=analytics \
DB_PASSWORD=analytics \
./gradlew bootRun -x test -x checkstyleMain -x pmdMain -x spotbugsMain
```

Open `http://localhost:8080` — sign in with GitHub. REST API requires `Authorization: Bearer your-secret-token`.

---

## Configuration

| Variable | Default | Description |
|---|---|---|
| `API_TOKEN` | `changeme-in-production` | Static bearer token for all REST API calls |
| `GITHUB_CLIENT_ID` | — | GitHub OAuth App client ID (web UI login) |
| `GITHUB_CLIENT_SECRET` | — | GitHub OAuth App client secret (web UI login) |
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
```

Token needs `read_api` scope. Test connectivity: `POST /api/v1/sources/gitlab/1/test`

### Step 2 — Register a project

GitLab project ID is visible in Settings or via `GET /api/v4/projects/<url-encoded-path>`.

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
```

### Step 3 — Register tracked users

Each team member needs a `TrackedUser` and at least one `TrackedUserAlias` linking their GitLab account.

```bash
curl -X POST http://localhost:8080/api/v1/users \
  -H "Authorization: Bearer your-secret-token" \
  -H "Content-Type: application/json" \
  -d '{"displayName": "Jane Smith", "email": "jane@example.com"}'

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

> **Important**: The `email` in the alias must match `author_email` in git commits — this is how commits are attributed. Verify with: `git log --format='%ae' | sort -u`. Commit emails and GitLab account emails often differ.

### Step 4 — Run a sync

```bash
curl -X POST http://localhost:8080/api/v1/sync/manual \
  -H "Authorization: Bearer your-secret-token" \
  -H "Content-Type: application/json" \
  -d '{
    "projectIds": [1],
    "dateFrom": "2025-10-01T00:00:00Z",
    "dateTo": "2026-04-17T23:59:59Z",
    "fetchNotes": true,
    "fetchApprovals": true,
    "fetchCommits": true
  }'
```

Poll status: `GET /api/v1/sync/jobs/{jobId}` until `status` is `COMPLETED` or `FAILED`.

> `fetchCommits: true` — один дополнительный вызов GitLab API на каждый коммит для получения diff-статистики. Уже загруженные коммиты повторно не запрашиваются.

### Step 5 — Generate metric snapshots (for History chart)

Snapshots are created automatically every day at 02:00 UTC. To backfill historical data manually:

```bash
# One snapshot per past date (repeat for each desired date)
curl -X POST http://localhost:8080/api/v1/snapshots/run \
  -H "Authorization: Bearer your-secret-token" \
  -H "Content-Type: application/json" \
  -d '{"snapshotDate": "2025-11-01", "windowDays": 30}'
```

The History chart shows one data point per snapshot date. Run snapshots every 1–2 weeks to get a smooth trend line.

### Step 6 — Get an API report

```bash
curl -X POST http://localhost:8080/api/v1/reports/contributions \
  -H "Authorization: Bearer your-secret-token" \
  -H "Content-Type: application/json" \
  -d '{
    "projectIds": [1],
    "userIds": [2, 3, 4],
    "periodPreset": "LAST_30_DAYS",
    "metrics": null
  }'
```

`periodPreset`: `LAST_7_DAYS` · `LAST_30_DAYS` · `LAST_90_DAYS` · `LAST_180_DAYS` · `LAST_360_DAYS` · `CUSTOM`
For `CUSTOM` add `"dateFrom"` and `"dateTo"` in ISO-8601 format (`Z`).

`metrics`: array of metric keys to include, or `null` for all.

---

## Metrics reference

### Доставка

| Метрика | Как считается | Как читать |
|---|---|---|
| `mr_merged_count` | Авторские MR с непустым `merged_at` в периоде | Завершённые доставки |
| `mr_opened_count` | Авторские MR, открытые в периоде | Объём открытых задач |
| `commits_in_mr_count` | Коммиты в авторских MR, где `author_email` совпадает с пользователем | Атрибуция по email коммита, не по GitLab-аккаунту |
| `active_days_count` | Уникальные календарные дни с коммитом, заметкой или апрувом | Реальные рабочие дни |
| `repositories_touched_count` | Уникальные проекты с авторскими MR | Кросс-проектная активность |

### Объём изменений

| Метрика | Как считается | Как читать |
|---|---|---|
| `lines_added` | Сумма `additions` по собственным коммитам | Сырой объём добавленного кода |
| `lines_deleted` | Сумма `deletions` по собственным коммитам | Высокие значения — рефакторинг или удаление легаси |
| `lines_changed` | `lines_added + lines_deleted` | Общий churn |
| `avg_mr_size_lines` | Среднее суммы (additions + deletions) по коммитам внутри каждого MR | Высокие значения — MR трудно ревьюировать |
| `median_mr_size_lines` | Медиана того же — менее чувствительна к выбросам | Большой разрыв с avg означает несколько гигантских MR |

### Ревью

| Метрика | Как считается | Как читать |
|---|---|---|
| `review_comments_written_count` | Несистемные заметки в **чужих** MR | Основная вовлечённость в ревью |
| `review_threads_started_count` | Заметки, первые в своём треде в чужом MR | Инициирование обсуждений, а не ответы на них |
| `mrs_reviewed_count` | Уникальные чужие MR хотя бы с одной заметкой или апрувом от пользователя | Охват ревью |
| `approvals_given_count` | Апрувы на чужие MR | — |

### Flow-метрики (только авторские MR)

| Метрика | Как считается | Как читать |
|---|---|---|
| `avg_time_to_first_review_minutes` | Среднее минут от `created_at` MR до первой внешней заметки или апрува | Долгое значение — MR-ы подолгу ждут внимания ревьюера |
| `median_time_to_first_review_minutes` | Медиана того же | Менее чувствительна к выбросам |
| `avg_time_to_merge_minutes` | Среднее минут от `created_at` до `merged_at` | End-to-end цикл |
| `median_time_to_merge_minutes` | Медиана того же | — |
| `rework_ratio` | `rework_mr_count / mr_merged_count` | 0.2 = 20% MR-ов получили коммиты после первого ревью |
| `rework_mr_count` | Авторские MR с хотя бы одним коммитом автора после первого внешнего ревью | — |

### Нормализованные

| Метрика | Как считается | Как читать |
|---|---|---|
| `mr_merged_per_active_day` | `mr_merged_count / active_days_count` | Пропускная способность относительно рабочих дней |
| `comments_per_reviewed_mr` | `review_comments_written_count / mrs_reviewed_count` | < 1 — в основном апрувы без комментариев; > 2 — содержательная обратная связь |

### Сравнение в команде (`teamComparison`)

Для `mr_merged_count`, `review_comments_written_count`, `approvals_given_count`, `mrs_reviewed_count` — поле `_percentile`: место пользователя среди участников текущего отчёта. `100.0` = наивысшее значение, `0.0` = наименьшее.

---

## Build

```bash
./gradlew build           # full build + tests + static analysis
./gradlew build -x test   # skip tests
./gradlew test            # tests only (requires Docker for Testcontainers)
./gradlew check -x test   # static analysis only
./gradlew clean build     # use when Flyway migration conflicts appear in tests
```
