#!/usr/bin/env bash
# db-reset-remote.sh — сбрасывает БД на удалённом сервере
#
# Использование:
#   ./scripts/db-reset-remote.sh          # только сброс БД
#   ./scripts/db-reset-remote.sh --full   # сброс + рестарт приложения

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'
info()    { echo -e "${CYAN}[db-reset-remote]${NC} $*"; }
success() { echo -e "${GREEN}[db-reset-remote]${NC} $*"; }
warn()    { echo -e "${YELLOW}[db-reset-remote]${NC} $*"; }
error()   { echo -e "${RED}[db-reset-remote]${NC} $*" >&2; }

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

ENV_FILE="${PROJECT_DIR}/.env.prod"
if [[ ! -f "${ENV_FILE}" ]]; then
  error ".env.prod не найден. Скопируйте .env.prod.example и заполните."
  exit 1
fi
# shellcheck source=/dev/null
source "${ENV_FILE}"

SERVER_IP="${SERVER_IP:?SERVER_IP не задан в .env.prod}"
SERVER_USER="${SERVER_USER:-root}"
REMOTE_DIR="/opt/gitlab-analytics"
COMPOSE_CMD="docker compose -f docker/docker-compose.prod.yml --project-name gitlab-analytics --env-file .env"

warn "⚠️  Это сбросит ВСЕ данные на ${SERVER_IP}!"
read -r -p "Введите 'yes' для подтверждения: " confirm
[[ "$confirm" == "yes" ]] || { info "Отменено."; exit 0; }

info "Подключаемся к ${SERVER_USER}@${SERVER_IP}..."
ssh "${SERVER_USER}@${SERVER_IP}" bash <<ENDSSH
set -e
cd ${REMOTE_DIR}

echo "Сбрасываем все таблицы..."
${COMPOSE_CMD} exec -T postgres \
  psql -U analytics -d gitlab_analytics <<'SQL'
DO \$\$
DECLARE r RECORD;
BEGIN
  FOR r IN (SELECT tablename FROM pg_tables WHERE schemaname = 'public' AND tablename != 'flyway_schema_history') LOOP
    EXECUTE 'DROP TABLE IF EXISTS ' || quote_ident(r.tablename) || ' CASCADE';
  END LOOP;
END \$\$;
DELETE FROM flyway_schema_history;
SQL
echo "БД очищена."

if [[ "${FULL}" == "true" ]]; then
  echo "Перезапускаем приложение..."
  ${COMPOSE_CMD} restart app
  for i in \$(seq 1 30); do
    if curl -sf http://localhost:8080/actuator/health/liveness > /dev/null 2>&1; then
      echo "Приложение запущено!"
      break
    fi
    sleep 2
  done
  ${COMPOSE_CMD} ps
fi
ENDSSH

success "Удалённая БД очищена на ${SERVER_IP}."
