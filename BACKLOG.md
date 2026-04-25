# BACKLOG — gitlens analytics

Задачи для автономного агента. Правила:
- Брать первую задачу со статусом `[ ]` сверху вниз
- После реализации: отметить `[x]`, добавить дату в скобках
- Запустить `./gradlew build` перед коммитом — если падает, откатить и пометить `[!]` с причиной
- Если задача непонятна или требует решений — пропустить, написать комментарий `<!-- BLOCKED: причина -->`

---

## Sprint: Compare page (High Priority)

### TASK-01: Compare — Radar Chart
**Статус:** `[ ]`

**Контекст:**
- Страница: `src/main/resources/templates/compare.html`
- Контроллер: `src/main/java/.../web/controller/CompareController.java`
- Сервис: `src/main/java/.../web/CompareService.java` (если существует, иначе создать в `web/`)

**Что сделать:**
Добавить Radar Chart на страницу `compare.html` с 5 осями: Velocity, Quality, Flow, Review, Deploy.
- Данные для осей рассчитать из существующих метрик (`Metric` enum):
  - **Velocity** = среднее от `MR_MERGED_COUNT` / period_days * 30 (нормализовать 0–100 относительно max по командам)
  - **Quality** = 100 - `REWORK_RATIO` * 100 (или 0 если нет данных)
  - **Review** = min(`REVIEW_DEPTH` / 3.0 * 100, 100) где `REVIEW_DEPTH` = среднее комментариев на MR
  - **Flow** = 100 - min(`MEDIAN_TTM_HOURS` / 168 * 100, 100) (168ч = 1 неделя)
  - **Deploy** = из DoraService — deploys_per_week нормализовать: ≥5→100, ≥2→75, ≥1→50, <1→25
- Chart.js `radar` тип, две команды на одном графике (разные цвета)
- Данные передать через Thymeleaf model как JSON (атрибут `radarData`), инжектировать через `th:inline="javascript"`
- Стиль: полупрозрачная заливка, gridlines в var(--border-color)

**Acceptance:** страница `/compare` открывается, radar chart рендерится с данными двух команд.

---

### TASK-02: Compare — TeamCompareChart (исторический тренд)
**Статус:** `[ ]`

**Контекст:**
- Зависит от TASK-01 только по размещению на странице, данные независимы
- Использовать `MetricSnapshot` таблицу для исторических данных

**Что сделать:**
Добавить линейный Chart.js-график "Health Trend" под radar chart.
- Ось X: последние 26 недель (недельные снапшоты)
- Ось Y: health score команды (0–100), формула как в `DoraService.buildServiceHealthData()`
- Одна линия на команду
- Backend: новый метод в `CompareService` (или `DoraService`) `getTeamHealthTrend(teamId, weeks)`:
  - Берёт снапшоты из `MetricSnapshotRepository` за 26 недель по `projectIds` команды
  - Считает health score на каждую точку по той же формуле что в `DoraService`
  - Возвращает `List<HealthTrendPoint>` record с `date` + `score`
- Данные в модель как `teamTrendData` JSON

**Acceptance:** под radar chart появляется линейный график с историей health score команд за 26 недель.

---

### TASK-03: Compare — Industry Benchmark Panel
**Статус:** `[ ]`

**Контекст:**
- Отдельный блок на странице compare.html внизу
- Бенчмарки берём из официальных DORA 2023 пороговых значений (уже есть в `DoraMetric` enum)

**Что сделать:**
Добавить панель "Industry Benchmarks" — 4 метрики vs DORA Elite/High/Medium пороги.
- Метрики: Lead Time for Changes, Deploy Frequency, Change Failure Rate, MTTR
- Для каждой: горизонтальный progress bar показывающий текущее значение команды vs DORA-пороги
- Источник данных: `DoraService` — взять существующие методы `buildLeadTimeData()`, `buildDeployFrequencyData()` и т.д.
- Показывать текущий рейтинг команды (ELITE / HIGH / MEDIUM / LOW) из `DoraRating` enum с цветным badge
- Если несколько команд — показать для каждой или для выбранной (dropdown)

**Acceptance:** панель бенчмарков отображается на `/compare`, значения соответствуют данным с DORA-страницы.

---

## Sprint: Auth (High Priority)

### TASK-04: Убрать GitHub OAuth — оставить только email+password
**Статус:** `[ ]`

**Контекст:**
- Текущий auth: GitHub OAuth2 (web) + Bearer token (API) + email+password уже есть (`b2a587a`)
- Файлы: `src/main/java/.../config/SecurityConfig.java`, `src/main/resources/templates/login.html`
- `AppUser` имеет `email` + `passwordHash` поля (добавлены в `b2a587a`)
- GitHub OAuth — когнитивный диссонанс для GitLab-продукта

**Что сделать:**
1. Убрать GitHub OAuth2 из `SecurityConfig` — оставить только form login (email+password)
2. Убрать `spring-boot-starter-oauth2-client` зависимость из `build.gradle.kts`
3. Убрать GitHub env vars (`GITHUB_CLIENT_ID`, `GITHUB_CLIENT_SECRET`) из `application.yml` и `application-test.yml`
4. Обновить `login.html` — убрать кнопку "Войти через GitHub", оставить форму email+password
5. Убрать фиктивные GitHub credentials из `application-test.yml`
6. Проверить что все тесты проходят: `./gradlew build`

**Acceptance:** `./gradlew build` проходит, страница `/login` показывает только email+password форму.

---

## Sprint: UX / Polish (Medium Priority)

### TASK-05: Роли и permissions — проверки для admin-действий
**Статус:** `[ ]`

**Контекст:**
- `WorkspaceMember.role` enum уже есть (`ADMIN` / `MEMBER`), но проверок нет
- Создатель workspace = ADMIN, остальные = MEMBER

**Что сделать:**
1. Добавить `@PreAuthorize` или ручные проверки в `SettingsController` — только ADMIN может:
   - Добавлять/удалять проекты
   - Добавлять/удалять сотрудников
   - Запускать синк / backfill
   - Управлять токенами
2. Обычный MEMBER может только читать (GET-эндпоинты)
3. В `settings.html` скрыть кнопки "Удалить" / "Добавить" для MEMBER через Thymeleaf `th:if`
4. При несанкционированном действии — 403 с понятным сообщением

**Acceptance:** MEMBER не может удалить проект/сотрудника ни через UI, ни через прямой POST-запрос.

---

### TASK-06: Command Palette (⌘K) — базовая навигация
**Статус:** `[ ]`

**Контекст:**
- В `topbar.html` уже есть placeholder кнопка ⌘K
- Нужен lightweight modal без внешних зависимостей (vanilla JS)

**Что сделать:**
1. Добавить модальное окно command palette в `fragments/topbar.html`
2. Ctrl+K / Cmd+K открывает палитру
3. Поиск по статичному списку команд/страниц:
   - "Обзор" → `/`
   - "Инсайты" → `/insights`
   - "Команда" → `/team`
   - "Flow" → `/flow`
   - "DORA / Сервисы" → `/dora`
   - "Сравнение" → `/compare`
   - "Синк / История" → `/sync`
   - "Настройки" → `/settings`
4. Fuzzy search по названию (простой `includes` фильтр)
5. Клавиши: Esc закрывает, Enter/стрелки навигируют, Enter переходит
6. Стиль: dark overlay, centered card, input с иконкой поиска, список результатов

**Acceptance:** Cmd+K открывает палитру, ввод "инс" показывает "Инсайты", Enter переходит на `/insights`.

---

## Sprint: Export (Low Priority)

### TASK-07: CSV export — страница Overview
**Статус:** `[ ]`

**Контекст:**
- Данные: таблица разработчиков с метриками из `ReportViewService`
- Добавить кнопку "Экспорт CSV" рядом с таблицей на `report.html`

**Что сделать:**
1. Новый endpoint `GET /report/export/csv?period=&projectIds=` в `WebController`
2. Возвращает `text/csv` с заголовком `Content-Disposition: attachment; filename=report.csv`
3. Столбцы: Имя, Email, MR merged, Lines added, Lines deleted, Avg TTM (hours), Review comments given, Review comments received
4. Данные из `ReportViewService.getReportData()` — тот же запрос что для страницы
5. Кнопка в `report.html`: `<a href="/report/export/csv?..." class="btn btn-sm">↓ CSV</a>` с текущими фильтрами

**Acceptance:** клик на кнопку скачивает CSV с данными текущего периода/проектов.

---

## Done

<!-- Выполненные задачи переносятся сюда -->
