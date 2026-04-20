# dev-reset

Перезапуск: очищает БД и перезапускает только приложение (Docker уже запущен).

Выполни шаги по порядку:

## 1. Остановить приложение

```bash
lsof -ti :8080 | xargs kill -9 2>/dev/null; pkill -f "gitlab-analytics" 2>/dev/null; sleep 2; echo "stopped"
```

## 2. Убедиться что Docker запущен, поднять postgres если нужно

```bash
docker info > /dev/null 2>&1 || (echo "Docker не запущен, запустите Docker Desktop" && exit 1)
docker compose up -d postgres 2>&1 | tail -2
sleep 3
```

## 3. Очистить БД

```bash
docker exec gitlab-analytics-postgres psql -U analytics -d gitlab_analytics -c "
DROP TABLE IF EXISTS merge_request_approval, merge_request_note, merge_request_discussion,
  merge_request_commit, merge_request, tracked_user_alias, metric_snapshot, sync_job,
  tracked_project, tracked_user, git_source, workspace_member, workspace, app_user CASCADE;
DELETE FROM flyway_schema_history;
" && echo "DB cleared"
```

## 4. Пересобрать приложение

```bash
cd /Users/igorsimakov/IdeaProjects/gitlab-analytics && \
./gradlew build -x test -x jacocoTestReport -x jacocoTestCoverageVerification -q && echo "Build OK"
```

## 5. Запустить приложение

```bash
GITHUB_CLIENT_ID=Ov23lio9aNycEibqhw5j \
GITHUB_CLIENT_SECRET=1e01f6c38e49658066e9076640c62847a3889140 \
DB_URL=jdbc:postgresql://localhost:5432/gitlab_analytics \
DB_USERNAME=analytics \
DB_PASSWORD=analytics \
java -jar /Users/igorsimakov/IdeaProjects/gitlab-analytics/build/libs/gitlab-analytics-0.1.0-SNAPSHOT.jar \
  --server.port=8080 \
  > /tmp/app.log 2>&1 &
echo "PID: $!"
```

## 6. Дождаться старта и проверить

```bash
sleep 10 && curl -s -o /dev/null -w "HTTP %{http_code}" http://localhost:8080/login && echo " — ready"
```

После выполнения сообщи пользователю что БД очищена и приложение запущено на http://localhost:8080
