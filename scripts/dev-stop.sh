#!/usr/bin/env bash
# dev-stop.sh — останавливает Spring Boot, запущенный через dev-start.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

RED='\033[0;31m'; GREEN='\033[0;32m'; CYAN='\033[0;36m'; NC='\033[0m'
info()    { echo -e "${CYAN}[dev-stop]${NC} $*"; }
success() { echo -e "${GREEN}[dev-stop]${NC} $*"; }
error()   { echo -e "${RED}[dev-stop]${NC} $*" >&2; }

PID_FILE="$ROOT_DIR/build/dev-app.pid"

if [[ -f "$PID_FILE" ]]; then
  PID=$(cat "$PID_FILE")
  if kill -0 "$PID" 2>/dev/null; then
    info "Останавливаем приложение (PID $PID)..."
    kill -TERM "$PID"
    for i in $(seq 1 10); do
      kill -0 "$PID" 2>/dev/null || break
      sleep 1
    done
    if kill -0 "$PID" 2>/dev/null; then
      kill -9 "$PID" 2>/dev/null || true
    fi
    success "Приложение остановлено"
  else
    info "Процесс $PID уже не запущен"
  fi
  rm -f "$PID_FILE"
else
  # fallback: убить по порту
  if lsof -ti:8080 > /dev/null 2>&1; then
    info "PID-файл не найден, убиваем процесс на порту 8080..."
    kill -TERM $(lsof -ti:8080) 2>/dev/null || true
    success "Приложение остановлено"
  else
    info "Приложение не запущено"
  fi
fi
