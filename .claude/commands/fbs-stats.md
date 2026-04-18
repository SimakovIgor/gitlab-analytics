Recalculate and display contribution statistics for the FBS team (Saltykov, Upatov, Simakov) across both tracked projects.

## Context

- Service URL: http://localhost:8080
- API token: local-test
- Projects: seller-order-service (id=1), seller-storage-service (id=2)
- Users: Saltykov (id=2), Upatov (id=3), Simakov (id=4)
- Default period: last 90 days (`LAST_90_DAYS`). If the user specifies a different period (e.g. "за январь", "last 30 days", specific dates), use `CUSTOM` with the appropriate `dateFrom`/`dateTo`.

## Steps to execute

### 1. Check that the service is running

```bash
curl -s http://localhost:8080/actuator/health | python3 -c "import sys,json; print(json.load(sys.stdin)['status'])"
```

If not UP, tell the user to start it:

```bash
API_TOKEN=local-test DB_URL=jdbc:postgresql://localhost:5432/gitlab_analytics DB_USERNAME=analytics DB_PASSWORD=analytics ./gradlew bootRun -x test -x checkstyleMain -x pmdMain -x spotbugsMain
```

### 2. Run a sync to get fresh data

Sync both projects for the requested period with commits, notes, and approvals:

```bash
curl -s -X POST http://localhost:8080/api/v1/sync/manual \
  -H "Authorization: Bearer local-test" \
  -H "Content-Type: application/json" \
  -d '{
    "projectIds": [1, 2],
    "dateFrom": "<resolved dateFrom>",
    "dateTo": "<resolved dateTo>",
    "fetchNotes": true,
    "fetchApprovals": true,
    "fetchCommits": true
  }'
```

Poll `GET /api/v1/sync/jobs/{jobId}` every 15 seconds until `status` is `COMPLETED` or `FAILED`. Show progress to the user.

### 3. Fetch the contribution report

```bash
curl -s -X POST http://localhost:8080/api/v1/reports/contributions \
  -H "Authorization: Bearer local-test" \
  -H "Content-Type: application/json" \
  -d '{
    "projectIds": [1, 2],
    "userIds": [2, 3, 4],
    "periodPreset": "<LAST_90_DAYS or CUSTOM>",
    "dateFrom": "<only for CUSTOM>",
    "dateTo": "<only for CUSTOM>",
    "groupBy": "USER",
    "reportMode": "MERGED_IN_PERIOD",
    "metrics": null
  }'
```

### 4. Present results

Display a markdown summary table followed by individual observations.

**Before the table**, output a header block with the scope of the report:

```
**Проекты:** seller-order-service, seller-storage-service
**Период:** <dateFrom> — <dateTo> (LAST_90_DAYS / CUSTOM)
**Режим:** MERGED_IN_PERIOD
```

**Summary table format:**

| Метрика                     | Saltykov | Upatov | Simakov |
|-----------------------------|----------|--------|---------|
| MR merged                   |          |        |         |
| Commits                     |          |        |         |
| Lines added                 |          |        |         |
| Lines deleted               |          |        |         |
| Avg MR size (lines)         |          |        |         |
| Median MR size (lines)      |          |        |         |
| Active days                 |          |        |         |
| Review comments             |          |        |         |
| MRs reviewed                |          |        |         |
| Approvals given             |          |        |         |
| Review threads started      |          |        |         |
| Median time to first review | min      | min    | min     |
| Median time to merge        | min      | min    | min     |
| Rework ratio                | %        | %      | %       |
| Self-merge ratio            | %        | %      | %       |
| MR merged / active day      |          |        |         |
| Comments / reviewed MR      |          |        |         |

Convert `rework_ratio` and `self_merge_ratio` to percentages (multiply by 100, round to 1 decimal).
Convert time metrics from minutes to hours if > 120 min (e.g. "8.5h"), otherwise show as "43 min".

After the table, add **3-5 bullet points** of observations — things that stand out, unexpected patterns, or comparisons between team members worth discussing at a retrospective. Be specific and
grounded in the numbers. Do not judge or score people.

Then add a **"Как считаются метрики"** section with the following table (copy verbatim):

---

### Как считаются метрики

| Метрика                         | Как считается                                                                                                |
|---------------------------------|--------------------------------------------------------------------------------------------------------------|
| **MR merged**                   | Авторские MR с непустым `merged_at` в периоде                                                                |
| **Commits**                     | Коммиты в авторских MR, где `author_email` совпадает с пользователем (по email, не по GitLab-аккаунту)       |
| **Lines added**                 | Сумма `additions` по собственным коммитам                                                                    |
| **Lines deleted**               | Сумма `deletions` по собственным коммитам                                                                    |
| **Avg MR size (lines)**         | Среднее суммы (additions + deletions) по **всем** коммитам внутри каждого MR (включая чужие коммиты в MR)    |
| **Median MR size (lines)**      | Медиана того же — менее чувствительна к выбросам                                                             |
| **Active days**                 | Уникальные UTC-дни, в которые был хотя бы один коммит, заметка или апрув от пользователя                     |
| **Review comments**             | Несистемные заметки (`system=false`) в **чужих** MR                                                          |
| **MRs reviewed**                | Уникальные чужие MR с хотя бы одной заметкой или апрувом от пользователя                                     |
| **Approvals given**             | Апрувы на чужие MR                                                                                           |
| **Review threads started**      | Заметки, первые в своём дискуссионном треде в чужом MR                                                       |
| **Median time to first review** | Медиана минут от `created_at` MR до первой внешней заметки или апрува (только для авторских MR)              |
| **Median time to merge**        | Медиана минут от `created_at` до `merged_at` (только для авторских MR)                                       |
| **Rework ratio**                | `rework_mr_count / mr_merged_count` — доля MR-ов, в которых автор пушил коммиты после первого внешнего ревью |
| **Self-merge ratio**            | `self_merge_count / mr_merged_count` — доля MR-ов, где `merged_by` совпадает с автором                       |
| **MR merged / active day**      | `mr_merged_count / active_days_count`                                                                        |
| **Comments / reviewed MR**      | `review_comments_written_count / mrs_reviewed_count`                                                         |
