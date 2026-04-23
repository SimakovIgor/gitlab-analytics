#!/usr/bin/env bash
# restart-remote.sh — перезапускает приложение на удалённом сервере
#
# Использование:
#   ./scripts/restart-remote.sh           # рестарт только app-контейнера
#   ./scripts/restart-remote.sh --all     # рестарт всего стека (postgres, grafana и т.д.)
#   ./scripts/restart-remote.sh --status  # только показать статус контейнеров

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

RED='\033[0;31m'; GREEN='\033[0;32m'; CYAN='\033[0;36m'; NC='\033[0m'
info()    { echo -e "${CYAN}[restart-remote]${NC} $*"; }
success() { echo -e "${GREEN}[restart-remote]${NC} $*"; }
error()   { echo -e "${RED}[restart-remote]${NC} $*" >&2; }

MODE="app"
for arg in "$@"; do
  case "$arg" in
    --all)    MODE="all" ;;
    --status) MODE="status" ;;
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
COMPOSE="docker compose -f ${REMOTE_DIR}/docker/docker-compose.prod.yml --project-name gitlab-analytics --env-file ${REMOTE_DIR}/.env"

info "Подключаемся к ${SERVER_USER}@${SERVER_IP}..."

case "$MODE" in
  status)
    ssh "${SERVER_USER}@${SERVER_IP}" "${COMPOSE} ps"
    ;;
  all)
    info "Перезапуск всего стека..."
    ssh "${SERVER_USER}@${SERVER_IP}" bash -c "
      set -e
      ${COMPOSE} down
      ${COMPOSE} up -d
      sleep 10
      ${COMPOSE} ps
    "
    success "Стек перезапущен."
    ;;
  app)
    info "Перезапуск app-контейнера..."
    ssh "${SERVER_USER}@${SERVER_IP}" bash -c "
      set -e
      ${COMPOSE} restart app
      sleep 8
      ${COMPOSE} ps app
    "
    success "Приложение перезапущено."
    ;;
esac

echo ""
echo "  App:        http://${SERVER_IP}:8080"
echo "  Grafana:    http://${SERVER_IP}:3000"
echo "  Health:     http://${SERVER_IP}:8080/actuator/health"
