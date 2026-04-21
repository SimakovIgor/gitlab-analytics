# UI Redesign Roadmap — gitlens analytics

Дизайн-хэндофф: `gitlab-analytics-handoff.zip` (21.04.2026).
Два варианта темы: **A — Command Center** (тёмная) и **B — Narrative** (светлая, warm off-white).
Бренд: **gitlens analytics** — новый логотип-многоугольник, gradient accent, новая типографика.

**Стратегия:** сначала переносим всё существующее на новый дизайн (Phase 0–2), только потом новый функционал (Phase 3+). Новые экраны пока показываются как disabled-табы.

---

## Phase 0 — Дизайн-система (основа)

- [ ] **CSS токены** — перенести `tokens.css` в `analytics.css`: переменные `--bg-*`, `--fg-*`, `--accent`, `--border-*`, `--r-*`, `--shadow-*`, `--good/warn/bad/info`, `--c-1..8`,
  `--font-sans/mono/display`
- [ ] **Две темы** — `[data-variant="A"]` (тёмная) и `[data-variant="B"]` (светлая) через CSS-атрибут на `<html>`; toggle сохраняется в `localStorage`
- [ ] **Google Fonts** — IBM Plex Sans (вар A), Inter (вар B), JetBrains Mono, Instrument Serif
- [ ] **Базовые UI-примитивы** в JS: SVG-Sparkline, Delta badge, Avatar/initials, StatusDot, RatingPill

---

## Phase 1 — Shell (новый layout)

- [ ] **Топ-навигация** (sticky, 52px):
    - Логотип "gitlens analytics" (SVG с gradient)
    - Горизонтальные табы: Обзор / Инсайты / Команда / Flow / Сервисы / Сравнение / Sync
    - Поиск-заглушка (⌘K placeholder)
    - Theme toggle A/B
    - User avatar
    - Sync-status pill (показывает последний джоб)
- [ ] **Левый сайдбар** (232px, sticky top-52):
    - Секция **Workspace** — название команды
    - Секция **Репозитории** — список с sync-dot (ok/stale/failed), кнопка "+ Добавить"
    - Секция **Сотрудники** — avatars + статус dot (good/warn/bad)
    - Виджет **"Public beta / FREE"** в низу
- [ ] **Sync Status Pill** — ok/running/partial/failed, клик → mini-попап с деталями

---

## Phase 2 — Перенос существующих страниц

### 2a — Онбординг (6 шагов)

Текущий: 5 шагов. Новый: 6 шагов по новому дизайну.

- [ ] Новый Stepper (прогресс-полоски + labels)
- [ ] **Step 1** — Workspace: название команды
- [ ] **Step 2** — Git-система: выбор GitLab + URL хоста
- [ ] **Step 3** — Репозиторий и доступ: URL + PAT + live-валидация
- [ ] **Step 4** — Синхронизация: прогресс фазы 1 (fast MR list)
- [ ] **Step 5** — Сотрудники: auto-discovered список + чекбоксы
- [ ] **Step 6** — Готово: welcome-экран + старт фазы 2 в фоне
- [ ] Редирект после онбординга → дашборд с phase2-баннером (вместо отдельной progress-страницы)

### 2b — Overview [бывший Report]

- [ ] Переименовать "Report" → "Обзор" в навигации
- [ ] Metric cards нового вида: label + большое число + sparkline + delta (3 варианта: trend/chart/plain)
- [ ] Таблица разработчиков: добавить колонки "+ строк", "− строк", "Ревью-комм.", "Отревьюил", "Аппрувов", "Актив дней", "Медиана ч", "Тренд"
- [ ] Insight-карточки (bad/warn/good/info) — алгоритмические, над таблицей или в колонке
- [ ] Фильтр периода: 7d / 30d / 90d / 180d / 360d
- [ ] Scope по репозиторию через сайдбар

### 2c — Sync History [бывший progress.html + sync в Settings]

- [ ] Экран "Sync" (таб в навигации): список джобов, StatusBadge, expandable row с деталями
- [ ] Встроить текущую progress-страницу в этот экран
- [ ] Sync-попап из шапки (мини-версия последнего джоба)

### 2d — Settings

- [ ] Перестилизовать под новый дизайн
- [ ] Секции: Workspace, Репозитории, Сотрудники, Токен, Синк

---

## Phase 3 — Новые экраны

> Сначала добавляются как **disabled-табы** с заглушкой "Скоро". Реализация — поочерёдно.

- [ ] **Insights** — полный список algo-инсайтов из реальных данных (stuck MR, review imbalance, slow merge time и т.д.). AI-инсайты — заглушка с Pro badge.
- [ ] **Команда** — grid из dev-карточек с фильтрами (Все / С проблемами / Звёзды)
- [ ] **Dev Profile** — drill-down: hero + ключевые метрики + список MR + heat-map активности по часам
- [ ] **Flow** — анализ узких мест: SVG-гистограмма по стадиям (coding→review→merge→deploy), stuck MRs, review load
- [ ] **Сервисы** — DORA per service (требует GitLab Deployments API)
- [ ] **Сравнение** — cross-team radar chart, period-vs-period delta

---

## Технические решения

| Вопрос          | Решение                                                                      |
|-----------------|------------------------------------------------------------------------------|
| Рендеринг       | Остаёмся на **Thymeleaf** (server-side), не мигрируем на React/SPA           |
| Интерактивность | Vanilla JS (Alpine.js-style inline логика, никаких фреймворков)              |
| CSS             | Новые design tokens → `analytics.css`, темы через `data-variant` на `<html>` |
| Charts          | Chart.js остаётся, добавляем SVG-sparklines (простые inline)                 |
| Command palette | Простой JS overlay с input + фильтрацией (Phase 2+)                          |

## Что НЕ делаем в ближайших фазах

- **AI-инсайты** — это Pro-фича, заглушка "Pro" badge
- **DORA/Deployments** — требует GitLab Deployments API, отдельная задача
- **Multi-workspace / billing** — "Free beta" хардкод
- **Bus factor, burnout detection** — Phase 3+
