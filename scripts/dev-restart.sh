#!/usr/bin/env bash
# dev-restart.sh — пересобирает и перезапускает ТОЛЬКО приложение (БД не трогает).
#
# Использование:
#   ./scripts/dev-restart.sh          # пересобрать + перезапустить
#   ./scripts/dev-restart.sh --fast   # без checkstyle/pmd/spotbugs

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'
info()    { echo -e "${CYAN}[dev-restart]${NC} $*"; }
success() { echo -e "${GREEN}[dev-restart]${NC} $*"; }
warn()    { echo -e "${YELLOW}[dev-restart]${NC} $*"; }

FAST=false
for arg in "$@"; do
  case "$arg" in
    --fast) FAST=true ;;
    --help|-h) sed -n '2,7p' "${BASH_SOURCE[0]}" | sed 's/^# \?//'; exit 0 ;;
    *) echo "Неизвестный аргумент: $arg" >&2; exit 1 ;;
  esac
done

# ── .env ─────────────────────────────────────────────────────────────────────
for env_file in "$ROOT_DIR/.env" "$ROOT_DIR/.env.local"; do
  if [[ -f "$env_file" ]]; then
    set -o allexport; source "$env_file"; set +o allexport
  fi
done

# ── Остановка приложения ─────────────────────────────────────────────────────
# lsof can return multiple PIDs (Gradle wrapper + JVM) — kill them all.
OLD_PIDS=$(lsof -ti:8080 2>/dev/null || true)
if [[ -n "$OLD_PIDS" ]]; then
  info "Останавливаем процессы на порту 8080 (PIDs: $(echo $OLD_PIDS | tr '\n' ' '))..."
  echo "$OLD_PIDS" | xargs kill -TERM 2>/dev/null || true

  # Wait until port 8080 is actually free (up to 15s).
  for i in {1..15}; do
    lsof -ti:8080 > /dev/null 2>&1 || break
    sleep 1
  done

  # Force-kill anything still holding the port.
  REMAINING=$(lsof -ti:8080 2>/dev/null || true)
  if [[ -n "$REMAINING" ]]; then
    warn "Принудительное завершение (PIDs: $(echo $REMAINING | tr '\n' ' '))..."
    echo "$REMAINING" | xargs kill -9 2>/dev/null || true
    sleep 1
  fi
  success "Порт 8080 освобождён"
else
  info "Порт 8080 свободен"
fi

# ── Сборка и запуск ──────────────────────────────────────────────────────────
cd "$ROOT_DIR"

APP_ENV=(
  SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-dev}"
  GITHUB_CLIENT_ID="${GITHUB_CLIENT_ID:-dev-mock-client-id}"
  GITHUB_CLIENT_SECRET="${GITHUB_CLIENT_SECRET:-dev-mock-client-secret}"
  DB_URL="jdbc:postgresql://localhost:5432/gitlab_analytics"
  DB_USERNAME="analytics"
  DB_PASSWORD="analytics"
  API_TOKEN="${API_TOKEN:-changeme-dev-only}"
)
[[ -n "${JIRA_BASE_URL:-}" ]]       && APP_ENV+=(JIRA_BASE_URL="$JIRA_BASE_URL")
[[ -n "${JIRA_USERNAME:-}" ]]       && APP_ENV+=(JIRA_USERNAME="$JIRA_USERNAME")
[[ -n "${JIRA_API_TOKEN:-}" ]]      && APP_ENV+=(JIRA_API_TOKEN="$JIRA_API_TOKEN")
[[ -n "${JIRA_PROJECT_KEY:-}" ]]    && APP_ENV+=(JIRA_PROJECT_KEY="$JIRA_PROJECT_KEY")
[[ -n "${JIRA_IMPACT_START_FIELD:-}" ]] && APP_ENV+=(JIRA_IMPACT_START_FIELD="$JIRA_IMPACT_START_FIELD")
[[ -n "${JIRA_IMPACT_END_FIELD:-}" ]]   && APP_ENV+=(JIRA_IMPACT_END_FIELD="$JIRA_IMPACT_END_FIELD")

GRADLE_ARGS="-x test -x jacocoTestReport -x jacocoTestCoverageVerification"
if [[ "$FAST" == true ]]; then
  GRADLE_ARGS="$GRADLE_ARGS -x checkstyleMain -x pmdMain -x spotbugsMain"
fi

LOG_FILE="$ROOT_DIR/build/dev-app.log"
mkdir -p "$ROOT_DIR/build"
# Rotate old log so `tail -f` below only shows the new process output,
# not the "BUILD FAILED" written by the just-killed Gradle JVM.
[[ -f "$LOG_FILE" ]] && mv "$LOG_FILE" "${LOG_FILE}.old"

info "Пересобираем и запускаем Spring Boot..."
env "${APP_ENV[@]}" ./gradlew bootRun $GRADLE_ARGS > "$LOG_FILE" 2>&1 &
BOOT_PID=$!
# Detach from terminal process group so Ctrl+C on `tail -f` below
# does not send SIGINT to the Gradle/Spring process.
disown "$BOOT_PID"
echo "$BOOT_PID" > "$ROOT_DIR/build/dev-app.pid"

success "PID: $BOOT_PID"
echo ""
info "App → http://localhost:8080"
echo ""
info "Логи (Ctrl+C — выйти из просмотра, приложение продолжит работу):"
tail -f "$LOG_FILE" || true
