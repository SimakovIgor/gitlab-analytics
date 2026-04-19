#!/usr/bin/env bash
# dev-start.sh — запускает всё локальное окружение для разработки:
#   PostgreSQL + Prometheus + Grafana (OrbStack) → Spring Boot (gradle bootRun)
#
# Использование:
#   ./scripts/dev-start.sh                  # полный запуск с мониторингом
#   ./scripts/dev-start.sh --no-monitoring  # только PostgreSQL + приложение
#   ./scripts/dev-start.sh --fast           # пропустить статический анализ (checkstyle/pmd/spotbugs)
#   ./scripts/dev-start.sh --jar            # запустить собранный jar вместо bootRun (быстрее)
#   ./scripts/dev-start.sh --fast --jar     # флаги можно комбинировать
#
# Переменные окружения (из .env.local или .env в корне, или заданные напрямую):
#   GITHUB_CLIENT_ID     — Client ID GitHub OAuth App  [обязательно]
#   GITHUB_CLIENT_SECRET — Client Secret GitHub OAuth App  [обязательно]
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
FAST=false
USE_JAR=false

for arg in "$@"; do
  case "$arg" in
    --no-monitoring) WITH_MONITORING=false ;;
    --fast)          FAST=true ;;
    --jar)           USE_JAR=true ;;
    --help|-h)
      sed -n '2,17p' "${BASH_SOURCE[0]}" | sed 's/^# \?//'
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
  error "Создайте .env.local в корне проекта:"
  error "  cp .env.example .env.local  # и заполните значения"
  exit 1
fi

# ── OrbStack / Docker ─────────────────────────────────────────────────────────
# Используем контекст orbstack; если не запущен — просим открыть вручную
export DOCKER_CONTEXT=orbstack
ORB_CLI="$HOME/.orbstack/bin/orb"
if ! "$ORB_CLI" status 2>/dev/null | grep -q Running; then
  warn "OrbStack VM не запущен. Запускаем..."
  "$ORB_CLI" start
  info "Ожидаем готовности OrbStack (до 20 сек)..."
  for i in $(seq 1 20); do
    sleep 1
    "$ORB_CLI" status 2>/dev/null | grep -q Running && break
    [[ $i -eq 20 ]] && { error "OrbStack не запустился. Попробуйте вручную: ~/.orbstack/bin/orb start"; exit 1; }
  done
fi

# ── PostgreSQL ─────────────────────────────────────────────────────────────────
info "Запускаем PostgreSQL..."
docker compose -f "$ROOT_DIR/docker/docker-compose.yml" up -d postgres

info "Ждём готовности PostgreSQL..."
until docker compose -f "$ROOT_DIR/docker/docker-compose.yml" exec -T postgres \
    pg_isready -U analytics -d gitlab_analytics -q 2>/dev/null; do
  sleep 1
done
success "PostgreSQL готов → localhost:5432"

# ── Prometheus + Grafana ───────────────────────────────────────────────────────
if [[ "$WITH_MONITORING" == true ]]; then
  info "Запускаем Prometheus + Grafana..."
  docker compose -f "$ROOT_DIR/docker/docker-compose.monitoring.yml" up -d
  success "Prometheus → http://localhost:9090"
  success "Grafana    → http://localhost:3000  (admin / admin)"
fi

# ── Spring Boot ───────────────────────────────────────────────────────────────
cd "$ROOT_DIR"

echo ""
echo -e "  ${GREEN}App${NC}        → http://localhost:8080"
echo -e "  ${GREEN}Swagger${NC}    → http://localhost:8080/swagger-ui.html"
echo -e "  ${GREEN}Health${NC}     → http://localhost:8080/actuator/health"
echo -e "  ${GREEN}Metrics${NC}    → http://localhost:8080/actuator/prometheus"
if [[ "$WITH_MONITORING" == true ]]; then
  echo -e "  ${GREEN}Grafana${NC}    → http://localhost:3000  (admin / admin)"
  echo -e "              dashboards: JVM / Spring Boot 3.x · Application (sync jobs, HTTP)"
fi
echo ""

APP_ENV=(
  GITHUB_CLIENT_ID="$GITHUB_CLIENT_ID"
  GITHUB_CLIENT_SECRET="$GITHUB_CLIENT_SECRET"
  DB_URL="jdbc:postgresql://localhost:5432/gitlab_analytics"
  DB_USERNAME="analytics"
  DB_PASSWORD="analytics"
  API_TOKEN="${API_TOKEN:-changeme-dev-only}"
)

if [[ "$USE_JAR" == true ]]; then
  JAR=$(ls "$ROOT_DIR"/build/libs/*.jar 2>/dev/null | grep -v plain | head -1)
  if [[ -z "$JAR" ]]; then
    warn "Jar не найден в build/libs, собираем сначала..."
    ./gradlew build -x test -x jacocoTestReport -x jacocoTestCoverageVerification \
      -x checkstyleMain -x pmdMain -x spotbugsMain -q
    JAR=$(ls "$ROOT_DIR"/build/libs/*.jar | grep -v plain | head -1)
  fi
  info "Запускаем jar: $(basename "$JAR")"
  env "${APP_ENV[@]}" java -jar "$JAR"
else
  GRADLE_ARGS="-x test -x jacocoTestReport -x jacocoTestCoverageVerification"
  if [[ "$FAST" == true ]]; then
    GRADLE_ARGS="$GRADLE_ARGS -x checkstyleMain -x pmdMain -x spotbugsMain"
  fi
  info "Запускаем Spring Boot (bootRun)..."
  env "${APP_ENV[@]}" ./gradlew bootRun $GRADLE_ARGS
fi
