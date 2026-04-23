#!/usr/bin/env bash
# deploy.sh — сборка, синхронизация и деплой на удалённый сервер
#
# Использование:
#   ./scripts/deploy.sh              # полный деплой
#   ./scripts/deploy.sh --db-reset   # сброс БД перед деплоем
#   ./scripts/deploy.sh --skip-build # пропустить сборку (использовать существующий JAR)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'
info()    { echo -e "${CYAN}[deploy]${NC} $*"; }
success() { echo -e "${GREEN}[deploy]${NC} $*"; }
warn()    { echo -e "${YELLOW}[deploy]${NC} $*"; }
error()   { echo -e "${RED}[deploy]${NC} $*" >&2; }

DB_RESET=false
SKIP_BUILD=false
for arg in "$@"; do
  case "$arg" in
    --db-reset)   DB_RESET=true ;;
    --skip-build) SKIP_BUILD=true ;;
    --help|-h)
      sed -n '2,7p' "${BASH_SOURCE[0]}" | sed 's/^# \?//'
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

# --- Сборка ---
if [[ "${SKIP_BUILD}" == "false" ]]; then
  info "Собираем JAR..."
  cd "${PROJECT_DIR}"
  ./gradlew bootJar -x test
  JAR_FILE="${PROJECT_DIR}/build/libs/app.jar"
  if [[ ! -f "${JAR_FILE}" ]]; then
    error "JAR не найден после сборки: ${JAR_FILE}"
    exit 1
  fi
  success "JAR собран: $(basename "${JAR_FILE}") ($(du -sh "${JAR_FILE}" | cut -f1))"
else
  JAR_FILE="${PROJECT_DIR}/build/libs/app.jar"
  if [[ ! -f "${JAR_FILE}" ]]; then
    error "--skip-build указан, но JAR не найден: ${JAR_FILE}"
    error "Запустите без --skip-build или выполните ./gradlew bootJar вручную."
    exit 1
  fi
  info "Сборка пропущена (--skip-build), используем: $(basename "${JAR_FILE}")"
fi

# --- Синхронизация файлов ---
info "Синхронизация файлов на ${SERVER_USER}@${SERVER_IP}:${REMOTE_DIR}..."
rsync -avz --delete \
  --exclude='.git' \
  --exclude='.gradle' \
  --exclude='.idea' \
  --exclude='*.iml' \
  --exclude='.env*' \
  --exclude='build/classes' \
  --exclude='build/resources' \
  --exclude='build/reports' \
  --exclude='build/tmp' \
  --exclude='build/test-results' \
  --exclude='build/generated' \
  "${PROJECT_DIR}/" "${SERVER_USER}@${SERVER_IP}:${REMOTE_DIR}/"

info "Копируем .env.prod на сервер..."
scp "${ENV_FILE}" "${SERVER_USER}@${SERVER_IP}:${REMOTE_DIR}/.env"

# --- Сброс БД (опционально) ---
if [[ "${DB_RESET}" == "true" ]]; then
  warn "⚠️  Сбрасываем БД на ${SERVER_IP}..."
  ssh "${SERVER_USER}@${SERVER_IP}" bash <<ENDSSH
set -e
cd ${REMOTE_DIR}
${COMPOSE_CMD} up -d postgres
sleep 3

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
  success "БД сброшена."
fi

# --- Запуск сервисов ---
info "Запускаем сервисы на сервере..."
ssh "${SERVER_USER}@${SERVER_IP}" bash <<ENDSSH
set -e
cd ${REMOTE_DIR}
${COMPOSE_CMD} up -d --build --remove-orphans

echo "Ожидаем запуска приложения..."
for i in \$(seq 1 30); do
  if curl -sf http://localhost:8080/actuator/health/liveness > /dev/null 2>&1; then
    echo "Приложение запущено! (попытка \$i)"
    break
  fi
  if [ \$i -eq 30 ]; then
    echo "WARN: Приложение не ответило за 60 секунд, проверьте логи"
    ${COMPOSE_CMD} logs --tail=30 app
  fi
  sleep 2
done

echo ""
echo "--- Статус контейнеров ---"
${COMPOSE_CMD} ps
ENDSSH

echo ""
success "Деплой завершён!"
echo "    App:        http://${SERVER_IP}:8080"
echo "    Grafana:    http://${SERVER_IP}:3000"
echo "    Prometheus: http://${SERVER_IP}:9090"
echo "    Portainer:  http://${SERVER_IP}:9000"
