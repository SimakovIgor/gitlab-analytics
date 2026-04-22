#!/usr/bin/env bash
# dev-start.sh — запускает локальное окружение для разработки:
#   PostgreSQL → Spring Boot (gradle bootRun)
#
# Использование:
#   ./scripts/dev-start.sh              # PostgreSQL + приложение (по умолчанию)
#   ./scripts/dev-start.sh --full       # + Prometheus + Grafana + Portainer + Loki
#   ./scripts/dev-start.sh --fast       # пропустить статический анализ (checkstyle/pmd/spotbugs)
#   ./scripts/dev-start.sh --jar        # запустить собранный jar вместо bootRun (быстрее)
#   ./scripts/dev-start.sh --fast --jar # флаги можно комбинировать
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
WITH_MONITORING=false
FAST=false
USE_JAR=false

for arg in "$@"; do
  case "$arg" in
    --full)  WITH_MONITORING=true ;;
    --fast)  FAST=true ;;
    --jar)   USE_JAR=true ;;
    --help|-h)
      sed -n '2,12p' "${BASH_SOURCE[0]}" | sed 's/^# \?//'
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

# ── Docker ────────────────────────────────────────────────────────────────────
if ! docker ps > /dev/null 2>&1; then
  error "Docker не запущен. Запустите Docker Desktop и повторите попытку."
  exit 1
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

# ── Prometheus + Grafana + Portainer (только с --full) ────────────────────────
if [[ "$WITH_MONITORING" == true ]]; then
  info "Запускаем Prometheus + Grafana + Portainer (--full режим)..."
  MONITORING_CONTAINERS=(gitlab-analytics-prometheus gitlab-analytics-grafana gitlab-analytics-portainer gitlab-analytics-loki gitlab-analytics-promtail)
  ALL_RUNNING=true
  for c in "${MONITORING_CONTAINERS[@]}"; do
    if ! docker ps --format '{{.Names}}' | grep -q "^${c}$"; then
      ALL_RUNNING=false
      break
    fi
  done
  if [[ "$ALL_RUNNING" == true ]]; then
    info "Мониторинг уже запущен"
  else
    STOPPED=()
    for c in "${MONITORING_CONTAINERS[@]}"; do
      if docker ps -a --format '{{.Names}}' | grep -q "^${c}$"; then
        STOPPED+=("$c")
      fi
    done
    if [[ ${#STOPPED[@]} -gt 0 ]]; then
      docker start "${STOPPED[@]}" > /dev/null
    fi
    MISSING=()
    for c in "${MONITORING_CONTAINERS[@]}"; do
      if ! docker ps -a --format '{{.Names}}' | grep -q "^${c}$"; then
        MISSING+=("$c")
      fi
    done
    if [[ ${#MISSING[@]} -gt 0 ]]; then
      docker compose -f "$ROOT_DIR/docker/docker-compose.monitoring.yml" up -d
    fi
  fi
  success "Prometheus → http://localhost:9090"
  success "Grafana    → http://localhost:3000  (admin / admin)"
  success "Portainer  → http://localhost:9000"
fi

# ── Проверка порта 8080 ────────────────────────────────────────────────────────
if lsof -ti:8080 > /dev/null 2>&1; then
  warn "Порт 8080 уже занят. Останавливаем процесс..."
  kill -TERM $(lsof -ti:8080) 2>/dev/null || true
  sleep 2
  if lsof -ti:8080 > /dev/null 2>&1; then
    kill -9 $(lsof -ti:8080) 2>/dev/null || true
    sleep 1
  fi
  success "Порт 8080 освобождён"
fi

# ── Spring Boot ───────────────────────────────────────────────────────────────
cd "$ROOT_DIR"

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
  info "Запускаем jar: $(basename "$JAR")  (Ctrl+C — остановить)"
  env "${APP_ENV[@]}" java -jar "$JAR"
else
  GRADLE_ARGS="-x test -x jacocoTestReport -x jacocoTestCoverageVerification"
  if [[ "$FAST" == true ]]; then
    GRADLE_ARGS="$GRADLE_ARGS -x checkstyleMain -x pmdMain -x spotbugsMain"
  fi
  LOG_FILE="$ROOT_DIR/build/dev-app.log"
  mkdir -p "$ROOT_DIR/build"

  info "Собираем и запускаем Spring Boot (лог: build/dev-app.log)..."
  nohup env "${APP_ENV[@]}" ./gradlew bootRun $GRADLE_ARGS > "$LOG_FILE" 2>&1 &
  BOOT_PID=$!
  echo "$BOOT_PID" > "$ROOT_DIR/build/dev-app.pid"
  info "PID: $BOOT_PID  (остановить: ./scripts/dev-stop.sh)"
  echo ""
  info "App     → http://localhost:8080"
  info "Swagger → http://localhost:8080/swagger-ui.html"
  echo ""
  info "Логи (Ctrl+C — выйти из просмотра, приложение продолжит работу):"
  tail -f "$LOG_FILE"
fi
