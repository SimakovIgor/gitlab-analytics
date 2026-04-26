#!/usr/bin/env bash
# prod-reset.sh — очищает БД и перезапускает приложение на удалённом сервере.
# Аналог dev-reset.sh для продакшн-окружения.
#
# Использование:
#   ./scripts/prod-reset.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'
info()    { echo -e "${CYAN}[prod-reset]${NC} $*"; }
success() { echo -e "${GREEN}[prod-reset]${NC} $*"; }
warn()    { echo -e "${YELLOW}[prod-reset]${NC} $*"; }
error()   { echo -e "${RED}[prod-reset]${NC} $*" >&2; }

ENV_FILE="${PROJECT_DIR}/.env.prod"
if [[ ! -f "${ENV_FILE}" ]]; then
  error ".env.prod не найден."
  exit 1
fi
# shellcheck source=/dev/null
source "${ENV_FILE}"

SERVER_IP="${SERVER_IP:?SERVER_IP не задан в .env.prod}"
SERVER_USER="${SERVER_USER:-root}"
REMOTE_DIR="/opt/gitlab-analytics"
COMPOSE_CMD="docker compose -f docker/docker-compose.prod.yml --project-name gitlab-analytics --env-file .env"

warn "⚠️  Это удалит ВСЕ данные на ${SERVER_IP} и перезапустит приложение!"
read -r -p "Введите 'yes' для подтверждения: " confirm
[[ "$confirm" == "yes" ]] || { info "Отменено."; exit 0; }

# ── 1. Очистить БД ─────────────────────────────────────────────────────────────
info "Очищаем базу данных на ${SERVER_IP}..."
ssh "${SERVER_USER}@${SERVER_IP}" bash <<ENDSSH
set -e
cd ${REMOTE_DIR}

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
ENDSSH
success "БД очищена"

# ── 2. Перезапустить приложение ────────────────────────────────────────────────
info "Перезапускаем приложение..."
ssh "${SERVER_USER}@${SERVER_IP}" bash <<ENDSSH
set -e
cd ${REMOTE_DIR}

${COMPOSE_CMD} restart app

echo "Ждём старта приложения..."
for i in \$(seq 1 30); do
  if curl -sf http://localhost:8080/actuator/health/liveness > /dev/null 2>&1; then
    echo "Приложение запущено! (попытка \$i)"
    break
  fi
  if [ \$i -eq 30 ]; then
    echo "WARN: Не ответило за 60 секунд, последние логи:"
    ${COMPOSE_CMD} logs --tail=20 app
  fi
  sleep 2
done
ENDSSH

echo ""
success "Готово! Прод сброшен и запущен."
echo -e "  ${CYAN}App${NC}  https://gitpulse.ru"
