#!/usr/bin/env bash
# dev-start.sh — запускает всё локальное окружение для разработки:
#   PostgreSQL + Prometheus + Grafana (Docker) → Spring Boot (gradle)
#
# Использование:
#   ./scripts/dev-start.sh
#   ./scripts/dev-start.sh --no-monitoring    # без Prometheus/Grafana
#   ./scripts/dev-start.sh --skip-build       # пропустить сборку, сразу bootRun
#
# Обязательные переменные окружения (или .env в корне проекта):
#   GITHUB_CLIENT_ID     — Client ID GitHub OAuth App
#   GITHUB_CLIENT_SECRET — Client Secret GitHub OAuth App
#
# Опциональные:
#   API_TOKEN            — Bearer-токен REST API (default: changeme-dev-only)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

# ── цвета ─────────────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'
info()    { echo -e "${CYAN}[dev-start]${NC} $*"; }
success() { echo -e "${GREEN}[dev-start]${NC} $*"; }
warn()    { echo -e "${YELLOW}[dev-start]${NC} $*"; }
error()   { echo -e "${RED}[dev-start]${NC} $*" >&2; }

# ── флаги ─────────────────────────────────────────────────────────────────────
WITH_MONITORING=true
SKIP_BUILD=false

for arg in "$@"; do
  case "$arg" in
    --no-monitoring) WITH_MONITORING=false ;;
    --skip-build)    SKIP_BUILD=true ;;
    --help|-h)
      sed -n '2,16p' "${BASH_SOURCE[0]}" | sed 's/^# \?//'
      exit 0
      ;;
    *) error "Неизвестный аргумент: $arg"; exit 1 ;;
  esac
done

# ── .env / .env.local ─────────────────────────────────────────────────────────
# .env.local перекрывает .env (загружается вторым)
for env_file in "$ROOT_DIR/.env" "$ROOT_DIR/.env.local"; do
  if [[ -f "$env_file" ]]; then
    info "Загружаем переменные из $(basename "$env_file")"
    set -o allexport
    # shellcheck disable=SC1090
    source "$env_file"
    set +o allexport
  fi
done

# ── проверка обязательных переменных ──────────────────────────────────────────
missing=()
[[ -z "${GITHUB_CLIENT_ID:-}" ]]     && missing+=("GITHUB_CLIENT_ID")
[[ -z "${GITHUB_CLIENT_SECRET:-}" ]] && missing+=("GITHUB_CLIENT_SECRET")

if [[ ${#missing[@]} -gt 0 ]]; then
  error "Не заданы обязательные переменные: ${missing[*]}"
  error "Задайте их в окружении или создайте .env в корне проекта:"
  error "  echo 'GITHUB_CLIENT_ID=...' >> .env"
  error "  echo 'GITHUB_CLIENT_SECRET=...' >> .env"
  exit 1
fi

# ── PostgreSQL ─────────────────────────────────────────────────────────────────
info "Запускаем PostgreSQL..."
docker compose -f "$ROOT_DIR/docker-compose.yml" up -d postgres

info "Ждём готовности PostgreSQL..."
until docker compose -f "$ROOT_DIR/docker-compose.yml" exec -T postgres \
    pg_isready -U analytics -d gitlab_analytics -q 2>/dev/null; do
  sleep 1
done
success "PostgreSQL готов → localhost:5432"

# ── Prometheus + Grafana ───────────────────────────────────────────────────────
if [[ "$WITH_MONITORING" == true ]]; then
  info "Запускаем Prometheus + Grafana..."
  docker compose -f "$ROOT_DIR/docker-compose.monitoring.yml" up -d
  success "Prometheus → http://localhost:9090"
  success "Grafana    → http://localhost:3000  (admin / admin)"
fi

# ── Spring Boot ───────────────────────────────────────────────────────────────
cd "$ROOT_DIR"

GRADLE_ARGS="-x test -x checkstyleMain -x pmdMain -x spotbugsMain"
if [[ "$SKIP_BUILD" == true ]]; then
  GRADLE_ARGS="$GRADLE_ARGS -x compileJava"
fi

info "Запускаем Spring Boot..."
echo ""
echo -e "  ${GREEN}App${NC}        → http://localhost:8080"
echo -e "  ${GREEN}Swagger${NC}    → http://localhost:8080/swagger-ui.html"
echo -e "  ${GREEN}Health${NC}     → http://localhost:8080/actuator/health"
echo -e "  ${GREEN}Prometheus${NC} → http://localhost:8080/actuator/prometheus"
if [[ "$WITH_MONITORING" == true ]]; then
  echo -e "  ${GREEN}Grafana${NC}    → http://localhost:3000  (admin / admin)"
fi
echo ""

GITHUB_CLIENT_ID="$GITHUB_CLIENT_ID" \
GITHUB_CLIENT_SECRET="$GITHUB_CLIENT_SECRET" \
DB_URL="jdbc:postgresql://localhost:5432/gitlab_analytics" \
DB_USERNAME="analytics" \
DB_PASSWORD="analytics" \
API_TOKEN="${API_TOKEN:-changeme-dev-only}" \
  ./gradlew bootRun $GRADLE_ARGS
