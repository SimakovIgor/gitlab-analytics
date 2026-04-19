#!/usr/bin/env bash
# db-reset-local.sh — сбрасывает локальную БД (все таблицы + flyway history)
#
# Использование:
#   ./scripts/db-reset-local.sh          # только сброс БД
#   ./scripts/db-reset-local.sh --full   # сброс + пересборка + запуск приложения

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'
info()    { echo -e "${CYAN}[db-reset]${NC} $*"; }
success() { echo -e "${GREEN}[db-reset]${NC} $*"; }
warn()    { echo -e "${YELLOW}[db-reset]${NC} $*"; }
error()   { echo -e "${RED}[db-reset]${NC} $*" >&2; }

FULL=false
for arg in "$@"; do
  case "$arg" in
    --full) FULL=true ;;
    --help|-h)
      sed -n '2,6p' "${BASH_SOURCE[0]}" | sed 's/^# \?//'
      exit 0
      ;;
    *) error "Неизвестный аргумент: $arg"; exit 1 ;;
  esac
done

export DOCKER_CONTEXT=orbstack

# Убеждаемся что postgres запущен
if ! docker compose -f "$ROOT_DIR/docker/docker-compose.yml" exec -T postgres \
    pg_isready -U analytics -q 2>/dev/null; then
  info "PostgreSQL не запущен, поднимаем..."
  docker compose -f "$ROOT_DIR/docker/docker-compose.yml" up -d postgres
  until docker compose -f "$ROOT_DIR/docker/docker-compose.yml" exec -T postgres \
      pg_isready -U analytics -q 2>/dev/null; do sleep 1; done
fi

warn "Сбрасываем все таблицы локальной БД..."
docker compose -f "$ROOT_DIR/docker/docker-compose.yml" exec -T postgres \
  psql -U analytics -d gitlab_analytics <<'SQL'
DROP TABLE IF EXISTS
  merge_request_approval,
  merge_request_note,
  merge_request_discussion,
  merge_request_commit,
  merge_request,
  tracked_user_alias,
  metric_snapshot,
  sync_job,
  tracked_project,
  tracked_user,
  git_source,
  workspace_member,
  workspace,
  app_user
CASCADE;
DELETE FROM flyway_schema_history;
SQL

success "Локальная БД очищена."

if [[ "$FULL" == true ]]; then
  info "Пересобираем приложение..."
  cd "$ROOT_DIR"
  ./gradlew build -x test -x jacocoTestReport -x jacocoTestCoverageVerification -q
  success "Сборка завершена. Запустите: ./scripts/dev-start.sh"
fi
