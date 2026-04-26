#!/usr/bin/env bash
# rollback.sh — откат прода на указанный коммит или тег
#
# Использование:
#   ./scripts/rollback.sh                  # показать последние 10 коммитов и выбрать
#   ./scripts/rollback.sh <commit|tag>     # откатиться на конкретный коммит/тег
#   ./scripts/rollback.sh --list           # показать последние 20 коммитов

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'
info()    { echo -e "${CYAN}[rollback]${NC} $*"; }
success() { echo -e "${GREEN}[rollback]${NC} $*"; }
warn()    { echo -e "${YELLOW}[rollback]${NC} $*"; }
error()   { echo -e "${RED}[rollback]${NC} $*" >&2; }

# --- Парсинг аргументов ---
TARGET_REF=""
LIST_ONLY=false
for arg in "$@"; do
  case "$arg" in
    --list) LIST_ONLY=true ;;
    --help|-h)
      sed -n '2,6p' "${BASH_SOURCE[0]}" | sed 's/^# \?//'
      exit 0
      ;;
    -*) error "Неизвестный аргумент: $arg"; exit 1 ;;
    *)  TARGET_REF="$arg" ;;
  esac
done

cd "${PROJECT_DIR}"

# --- Показать список коммитов ---
show_commits() {
  local n="${1:-10}"
  echo ""
  echo -e "${CYAN}Последние ${n} коммитов:${NC}"
  git log --oneline --decorate -"${n}"
  echo ""
}

if [[ "${LIST_ONLY}" == "true" ]]; then
  show_commits 20
  exit 0
fi

# --- Если цель не указана — показать список и предложить выбор ---
if [[ -z "${TARGET_REF}" ]]; then
  show_commits 10
  read -r -p "Введите коммит/тег для отката (или Enter для отмены): " TARGET_REF
  [[ -n "${TARGET_REF}" ]] || { info "Отменено."; exit 0; }
fi

# --- Проверить что ref существует ---
if ! git rev-parse --verify "${TARGET_REF}" > /dev/null 2>&1; then
  error "Коммит/тег не найден: ${TARGET_REF}"
  exit 1
fi

FULL_SHA=$(git rev-parse "${TARGET_REF}")
SHORT_SHA=$(git rev-parse --short "${TARGET_REF}")
COMMIT_MSG=$(git log --format="%s" -1 "${TARGET_REF}")
CURRENT_SHA=$(git rev-parse --short HEAD)

echo ""
warn "⚠️  Откат прода:"
echo -e "  Текущий: ${YELLOW}${CURRENT_SHA}${NC}"
echo -e "  Цель:    ${CYAN}${SHORT_SHA}${NC} — ${COMMIT_MSG}"
echo ""
read -r -p "Продолжить? (yes/no): " confirm
[[ "$confirm" == "yes" ]] || { info "Отменено."; exit 0; }

# --- Сохранить текущую ветку / состояние ---
ORIGINAL_BRANCH=$(git symbolic-ref --short HEAD 2>/dev/null || echo "")
if [[ -n "$(git status --porcelain)" ]]; then
  warn "Есть незакоммиченные изменения — сохраняем через git stash..."
  git stash push -m "rollback-stash-$(date +%s)"
  STASHED=true
else
  STASHED=false
fi

cleanup() {
  if [[ -n "${ORIGINAL_BRANCH}" ]]; then
    info "Возвращаемся на ветку ${ORIGINAL_BRANCH}..."
    git checkout "${ORIGINAL_BRANCH}"
  fi
  if [[ "${STASHED}" == "true" ]]; then
    info "Восстанавливаем stash..."
    git stash pop
  fi
}
trap cleanup EXIT

# --- Переключиться на целевой коммит ---
info "Переключаемся на ${SHORT_SHA}..."
git checkout --detach "${FULL_SHA}"

# --- Сборка ---
info "Собираем JAR для ${SHORT_SHA}..."
./gradlew bootJar -x test
JAR_FILE="${PROJECT_DIR}/build/libs/app.jar"
if [[ ! -f "${JAR_FILE}" ]]; then
  error "JAR не найден после сборки"
  exit 1
fi
success "JAR собран ($(du -sh "${JAR_FILE}" | cut -f1))"

# --- Деплой через основной скрипт ---
info "Запускаем деплой..."
trap - EXIT  # отключаем trap перед вызовом deploy.sh (cleanup будет в конце)
"${SCRIPT_DIR}/deploy.sh" --skip-build

# --- Возврат на оригинальную ветку ---
if [[ -n "${ORIGINAL_BRANCH}" ]]; then
  info "Возвращаемся на ветку ${ORIGINAL_BRANCH}..."
  git checkout "${ORIGINAL_BRANCH}"
fi
if [[ "${STASHED}" == "true" ]]; then
  info "Восстанавливаем stash..."
  git stash pop
fi

echo ""
success "Откат завершён! Прод работает на коммите ${SHORT_SHA}."
warn "Не забудьте: HEAD на main всё ещё указывает на ${CURRENT_SHA}."
