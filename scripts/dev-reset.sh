#!/usr/bin/env bash
# dev-reset.sh — убивает приложение, очищает БД, запускает заново.
# Docker (postgres) должен быть уже запущен.
#
# Использование:
#   ./scripts/dev-reset.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'
info()    { echo -e "${CYAN}[dev-reset]${NC} $*"; }
success() { echo -e "${GREEN}[dev-reset]${NC} $*"; }
warn()    { echo -e "${YELLOW}[dev-reset]${NC} $*"; }
error()   { echo -e "${RED}[dev-reset]${NC} $*" >&2; }

# ── .env / .env.local ──────────────────────────────────────────────────────────
for env_file in "$ROOT_DIR/.env" "$ROOT_DIR/.env.local"; do
  if [[ -f "$env_file" ]]; then
    set -o allexport
    # shellcheck disable=SC1090
    source "$env_file"
    set +o allexport
  fi
done

# GitHub credentials are only required outside the dev profile.
SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-dev}"
if [[ "$SPRING_PROFILES_ACTIVE" != *dev* ]]; then
  missing=()
  [[ -z "${GITHUB_CLIENT_ID:-}" ]]     && missing+=("GITHUB_CLIENT_ID")
  [[ -z "${GITHUB_CLIENT_SECRET:-}" ]] && missing+=("GITHUB_CLIENT_SECRET")
  if [[ ${#missing[@]} -gt 0 ]]; then
    error "Не заданы переменные: ${missing[*]}"
    error "Создайте .env.local: cp .env.example .env.local"
    exit 1
  fi
fi

# ── 1. Убить приложение ────────────────────────────────────────────────────────
OLD_PIDS=$(lsof -ti:8080 2>/dev/null || true)
if [[ -n "$OLD_PIDS" ]]; then
  info "Останавливаем процессы на порту 8080 (PIDs: $(echo "$OLD_PIDS" | tr '\n' ' '))..."
  echo "$OLD_PIDS" | xargs kill -TERM 2>/dev/null || true
  for i in {1..15}; do
    lsof -ti:8080 > /dev/null 2>&1 || break
    sleep 1
  done
  REMAINING=$(lsof -ti:8080 2>/dev/null || true)
  if [[ -n "$REMAINING" ]]; then
    echo "$REMAINING" | xargs kill -9 2>/dev/null || true
    sleep 1
  fi
  success "Порт 8080 освобождён"
else
  info "Приложение не запущено, пропускаем"
fi

# ── 2. Postgres ────────────────────────────────────────────────────────────────
if ! docker ps > /dev/null 2>&1; then
  error "Docker не запущен. Запустите Docker Desktop и повторите."
  exit 1
fi

info "Запускаем PostgreSQL..."
docker compose -f "$ROOT_DIR/docker/docker-compose.yml" up -d postgres > /dev/null 2>&1

info "Ждём готовности PostgreSQL..."
until docker compose -f "$ROOT_DIR/docker/docker-compose.yml" exec -T postgres \
    pg_isready -U analytics -d gitlab_analytics -q 2>/dev/null; do
  sleep 1
done
success "PostgreSQL готов"

# ── 3. Очистить БД ─────────────────────────────────────────────────────────────
info "Очищаем базу данных..."
docker compose -f "$ROOT_DIR/docker/docker-compose.yml" exec -T postgres \
  psql -U analytics -d gitlab_analytics -c "
DROP TABLE IF EXISTS
  merge_request_approval, merge_request_note, merge_request_discussion,
  merge_request_commit, merge_request, release_tag, tracked_user_alias, metric_snapshot,
  jira_incident, sync_job, team_project,
  tracked_project, tracked_user, team, git_source, workspace_member, workspace, app_user
CASCADE;
DELETE FROM flyway_schema_history;
" > /dev/null
success "БД очищена"

# ── 4. Собрать ─────────────────────────────────────────────────────────────────
info "Собираем jar..."
cd "$ROOT_DIR"
./gradlew build -x test -x jacocoTestReport -x jacocoTestCoverageVerification \
  -x checkstyleMain -x pmdMain -x spotbugsMain -q
success "Сборка OK"

# ── 5. Запустить ───────────────────────────────────────────────────────────────
JAR=$(ls "$ROOT_DIR"/build/libs/*.jar 2>/dev/null | grep -v plain | head -1)
LOG_FILE="/tmp/app.log"

info "Запускаем приложение (лог: $LOG_FILE)..."
env \
  SPRING_PROFILES_ACTIVE="$SPRING_PROFILES_ACTIVE" \
  GITHUB_CLIENT_ID="${GITHUB_CLIENT_ID:-dev-mock-client-id}" \
  GITHUB_CLIENT_SECRET="${GITHUB_CLIENT_SECRET:-dev-mock-client-secret}" \
  DB_URL="jdbc:postgresql://localhost:5432/gitlab_analytics" \
  DB_USERNAME="analytics" \
  DB_PASSWORD="analytics" \
  API_TOKEN="${API_TOKEN:-changeme-dev-only}" \
  java -jar "$JAR" --server.port=8080 \
  > "$LOG_FILE" 2>&1 &

APP_PID=$!
disown "$APP_PID"
info "PID: $APP_PID"

# ── 6. Дождаться старта ────────────────────────────────────────────────────────
info "Ждём старта приложения..."
for i in $(seq 1 30); do
  if curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/login 2>/dev/null | grep -q "200"; then
    echo ""
    success "Приложение запущено → http://localhost:8080"
    exit 0
  fi
  if ! kill -0 "$APP_PID" 2>/dev/null; then
    error "Процесс упал. Последние строки лога:"
    tail -20 "$LOG_FILE" | grep -v "Loki\|loki4j\|ConnectException\|ClosedChannel" || true
    exit 1
  fi
  printf "."
  sleep 2
done

error "Приложение не стартовало за 60 секунд. Лог: $LOG_FILE"
exit 1
