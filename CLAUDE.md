# CLAUDE.md

Этот файл содержит инструкции для Claude Code (claude.ai/code) при работе с данным репозиторием.

## Команды сборки

```bash
# Полная сборка со всеми проверками и тестами
./gradlew build

# Сборка без тестов
./gradlew build -x test

# Запустить все тесты
./gradlew test

# Только статический анализ (Checkstyle + PMD + SpotBugs)
./gradlew check -x test

# Запустить один тестовый класс
./gradlew test --tests "io.simakov.analytics.api.controller.SyncControllerTest"

# Чистая сборка (использовать при конфликтах Flyway-миграций в тестах)
./gradlew clean build
```

## Качество кода

Статический анализ запускается автоматически при сборке:

- **Checkstyle 10.17.0**: `config/checkstyle/checkstyle.xml`
- **PMD 7.4.0**: `config/pmd/pmd-rules.xml`
- **SpotBugs 4.8.6**: `config/spotbugs/spotbugs-exclude.xml`

### Ключевые правила Checkstyle

- `LineLength max=199` (импорты и URL исключены)
- `NeedBraces` — фигурные скобки обязательны для всех управляющих конструкций (`if`, `for`, `while` и т.д.)
- `OperatorWrap option=NL` — бинарные операторы переносятся на **следующую** строку
- `SeparatorWrap DOT option=nl` — `.` в цепочках методов переносится на следующую строку
- `AvoidStarImport` — только явные импорты
- `CustomImportOrder`: `THIRD_PARTY_PACKAGE ### STANDARD_JAVA_PACKAGE ### STATIC` (алфавитный порядок внутри групп, пустая строка между группами)
- `IllegalCatch` — подавлять через `@SuppressWarnings("checkstyle:IllegalCatch")` там, где необходимо
- `MethodLength max=100`, `JavaNCSS methodMaximum=60`, `CyclomaticComplexity max=15`
- Запрещённые импорты: `javax.transaction.Transactional`, JUnit 4, Hamcrest, `org.junit.jupiter.api.Assertions.*`
- Разрешённые аббревиатуры: `SSD, ID, DTO, API, MR, IT, DB, URL, UTC`

Для подавления через аннотацию: `@SuppressWarnings("checkstyle:RuleName")` (работает через `SuppressWarningsFilter`).

## Архитектура

Слоистая структура пакетов (без ArchUnit-проверок):

```
Controller → Service → Repository → Model
```

- **`api/controller/`** — REST-эндпоинты (`/api/v1/**`), делегируют в сервисы
- **`api/dto/`** — request/response DTO (JPA-сущности наружу не выставляются)
- **`api/exception/`** — `GlobalExceptionHandler`, кастомные исключения
- **`config/`** — Spring-конфигурация
- **`domain/model/`** — JPA-сущности + enum-ы
- **`domain/repository/`** — Spring Data JPA репозитории
- **`gitlab/client/`** — `GitLabApiClient` (WebClient, реактивный)
- **`gitlab/dto/`** — DTO ответов GitLab API
- **`metrics/`** — `MetricCalculationService`, `Metric` enum
- **`snapshot/`** — `SnapshotService` (ежедневный планировщик + ручной API), `SnapshotHistoryService`
- **`sync/`** — `SyncOrchestrator`, `SyncJobService`
- **`web/controller/`** — Thymeleaf-контроллеры (`WebController`, `HistoryController`, `SettingsController`)
- **`web/`** — `ContributorDiscoveryService`, `UserAliasService`, `ReportViewService`
- **`web/dto/`** — `ReportPageData`, `MrSummaryDto`, `HistoryPageData`, `SettingsPageData` и др.
- **`insights/`** — `InsightService`, `InsightEvaluator` интерфейс, 10 `@Component`-реализаций в `evaluator/`, `InsightRule` enum, `InsightProperties`, модели в `model/`
- **`security/`** — `BearerTokenAuthFilter`
- **`encryption/`** — интерфейс `EncryptionService` + `NoOpEncryptionService`

Шаблоны: `src/main/resources/templates/` (login, report, history, settings, insights, dora)
Статика: `src/main/resources/static/css/analytics.css`

## Ключевые паттерны

**Два Security filter chain** (порядок важен):

- `@Order(1)` — API-цепочка: `securityMatcher("/api/**")`, stateless, Bearer-токен, 401 при неудаче
- `@Order(2)` — Web-цепочка: OAuth2 через GitHub, сессионная, редирект на `/login`

**Metric enum** (`metrics/model/Metric.java`) — единственный источник истины для всех метрик. Каждая метрика имеет `key()` (ключ JSON/БД), `label()` (русская метка в UI), `description()` (русский текст легенды), `category()`, `isInMinutes()`, `isChartVisible()`. Везде использовать `Metric.XXX.key()` вместо строковых литералов. `Metric.chartOptions()` строит выпадающий список истории; `Metric.minuteKeys()` возвращает метрики, хранящиеся в минутах.

**PeriodType enum** — имеет метод `toDays()`. Использовать `PeriodType.valueOf(str).toDays()` вместо switch-выражений. Значения: `LAST_7_DAYS(7)`, `LAST_30_DAYS(30)`, `LAST_90_DAYS(90)`, `LAST_180_DAYS(180)`, `LAST_360_DAYS(360)`, `CUSTOM(0)`.

**jsonb-колонки**: использовать `@JdbcTypeCode(SqlTypes.JSON)` на `String`-полях, маппящихся в `jsonb`-колонки PostgreSQL. Без этого Hibernate 6 бросает ошибку несовпадения типов.

**Асинхронный синк**: `SyncOrchestrator.orchestrateAsync` выполняется в отдельном пуле потоков (настроен в `AsyncConfig`). Избегать `@Transactional` на `private`-методах — Spring AOP не может проксировать self-invocations.

**Пулы потоков** (настраиваются в `AsyncConfig`):
- `mrProcessingExecutor` — 10 потоков, очередь 500, **CallerRunsPolicy** (вызывающий поток выполняет задачу при заполнении очереди — предотвращает потерю задач для репозиториев с 3000+ MR).
- `commitStatsExecutor` — 7 потоков (I/O-bound), используется `CommitSyncStep` для параллельных вызовов `GET /repository/commits/:sha` (~2100 запросов/мин, в пределах лимита GitLab). Заменил последовательный цикл, который давал ~37 мин синка на больших репозиториях.

**Идемпотентный синк**: `SyncJobService.findActiveJobForProjects()` проверяет наличие STARTED-джоба с пересекающимися `projectIds` перед созданием нового. `SyncController` и `SettingsService.triggerBackfill()` оба вызывают эту проверку — двойной клик или две вкладки браузера возвращают существующий джоб вместо запуска дубля (который вызвал бы гонку `DataIntegrityViolationException` на уникальных ограничениях).

**Страница прогресса синка**: `GET /settings/sync/progress/{jobId}` — полноэкранная Thymeleaf-страница с поллингом каждые 3 секунды. Показывает неопределённый прогресс → точный прогресс после получения количества MR, ETA, прошедшее время, состояния завершения/ошибки с кнопкой повтора. Онбординг перенаправляет сюда после `addProject()` вместо inline-баннера (который был невидим на больших репозиториях).

**Шифрование**: `NoOpEncryptionService` хранит токены в открытом виде. Установить `app.encryption.enabled=true` и предоставить настоящий бин `EncryptionService` для продакшна.

**Статистика коммитов**: эндпоинты GitLab для списка MR и одного MR не возвращают `additions`/`deletions` на этом инстансе GitLab. Статистика запрашивается по одному через `GET /repository/commits/:sha` (один вызов на каждый новый коммит при синке). `MetricCalculationService` вычисляет `lines_added/deleted` из статистики коммитов, а не MR.

**Таймаут GitLab API**: `app.gitlab.read-timeout-seconds` (по умолчанию 30с). Увеличить до 120с+ для медленных инстансов GitLab. Полный синк с коммитами для 450+ MR занимает ~3–4 минуты при параллельной обработке (пул `mrProcessingExecutor`).

**Атрибуция коммитов**: `isUserCommit` сопоставляет коммиты по email. Проверяет как `TrackedUser.email`, так и все значения `TrackedUserAlias.email` (в нижнем регистре). Email в коммитах часто отличается от email GitLab-аккаунта — всегда регистрировать правильный email коммита в алиасе.

**Атрибуция MR**: выполняется по `author_gitlab_user_id` (хранится в каждом `MergeRequest`). При добавлении `TrackedUser` метод `UserAliasService.saveAlias()` вызывает `GitLabApiClient.findUserIdByUsername()`, используя username-часть email (например, `a.upatov` из `a.upatov@uzum.com`), чтобы найти и сохранить `gitlab_user_id` в `tracked_user_alias`. Если префикс email не совпадает с GitLab-именем пользователя — `gitlab_user_id` будет null и атрибуция MR не сработает.

**Поля `ManualSyncRequest`**: `fetchNotes`, `fetchApprovals`, `fetchCommits` (не `syncNotes`/`syncCommits`). По умолчанию все `false` — нужно явно устанавливать `true` для полного синка. `SettingsController.triggerBackfill()` устанавливает все три в `true`.

**Бэкфилл снапшотов**: снапшоты создаются ежедневно планировщиком (`app.snapshot.cron`, по умолчанию `0 0 2 * * *`). Два эндпоинта для ручного управления:

- `POST /api/v1/snapshots/backfill?days=360` — создаёт еженедельные снапшоты за последние N дней (шаг=7д). Автоматически запускается из UI при добавлении пользователей в онбординге. Основной бэкфилл для графика Истории.
- `POST /api/v1/snapshots/run` — создаёт один снапшот для конкретной `snapshotDate` и `windowDays`.
  График Истории читает из таблицы `metric_snapshot` — если данные кажутся пропущенными, проверить наличие снапшотов за нужный диапазон дат.

**Thymeleaf + Chart.js**: данные графика Истории передаются как JSON-строка через атрибут модели `chartData`, инжектируются в JS через `th:inline="javascript"` + `JSON.parse([[${chartData}]])`. Фильтр периода на странице Истории использует дни (7/30/90/180/360), на странице Отчёта — строковые значения `PeriodType`.

**MR drill-down**: `GET /report/user/{id}/mrs?period=&projectIds=` — возвращает `List<MrSummaryDto>` (JSON). Разрешает `gitlabUserId` через `TrackedUserAlias`, запрашивает `MergeRequestRepository.findMergedInPeriodByAuthors()`. Вызывается JS в report.html по клику на строку, рендерится в модальном окне.

**Клик по строке отчёта**: открывает модальное окно со списком смёрженных MR пользователя за текущий период/фильтры. График при этом НЕ меняется (управляется только селектором метрики и кнопками периода).

**Страница Инсайты** (`GET /insights`): паттерн Стратегия — `InsightService` собирает все бины `List<InsightEvaluator>` и запускает каждый. Контекст `InsightContext` record передаётся всем evaluator-ам и содержит: `users`, `current`/`previous` `Map<Long, UserMetrics>`, `openMrs`, `gitlabUserIdToTrackedUserId`. Результаты сортируются по `severity` по убыванию.

**`InsightRule` enum** (`insights/model/InsightRule.java`) — единственный источник истины для всех 10 правил (аналог `Metric` enum). Каждое правило имеет `code()`, `defaultKind()` (BAD/WARN/INFO/GOOD), `defaultSeverity()` (1–5), `description()`. Реализованные правила:

| Rule | Kind | Severity | Описание |
|------|------|----------|----------|
| `HIGH_MERGE_TIME` | BAD | 4 | Командная медиана TTM выше порога |
| `MERGE_TIME_SPIKE` | BAD | 4 | Рост медианы TTM относительно прошлого периода |
| `STUCK_MRS` | BAD | 5 | Открытые MR без движения дольше порога |
| `REVIEW_LOAD_IMBALANCE` | WARN | 3 | Неравномерное распределение ревью (коэффициент Джини) |
| `LARGE_MR_HABIT` | WARN | 2 | Средний размер MR превышает порог |
| `DELIVERY_DROP` | WARN | 3 | Снижение кол-ва MR относительно прошлого периода |
| `LOW_REVIEW_DEPTH` | INFO | 2 | Среднее кол-во комментариев на MR ниже порога |
| `HIGH_REWORK_RATIO` | WARN | 3 | Высокая доля доработок |
| `INACTIVE_MEMBER` | WARN | 2 | Участник без MR в текущем периоде |
| `NO_CODE_REVIEW` | BAD | 3 | MR мержатся без ревью |

**`InsightProperties`** (`@ConfigurationProperties(prefix = "app.insights.thresholds")`): все пороги вынесены в `application.yml`. Ключи: `stuck-mr-hours`, `max-median-ttm-hours`, `merge-time-spike-ratio`, `review-gini`, `large-mr-lines`, `delivery-drop-ratio`, `min-comments-per-mr`, `max-rework-ratio`, `min-mrs-for-no-review-check`.

**Добавить новое правило**: создать `@Component class MyEvaluator implements InsightEvaluator`, добавить константу в `InsightRule` — `InsightService` подхватит автоматически через коллекцию.

**topbar `activePage`**: принимает значения `'overview' | 'insights' | 'dora' | 'sync' | 'history' | 'settings'`.

## База данных

PostgreSQL + Flyway. Миграции в `src/main/resources/db/migration/`:

- `V1__initial_schema.sql` — полная схема
- `V2__fix_indexes.sql` — исправление индексов (удалены дублирующие, добавлены частичные индексы на nullable FK-колонки)
- `V3__add_snapshot_fields.sql` — добавляет `window_days`, `date_from`, `date_to`, `scope_type` в `metric_snapshot`
- `V4__drop_report_mode.sql` — удаляет колонку `report_mode` (теперь всегда MERGED_IN_PERIOD)
- `V5__cascade_deletes.sql` — каскадные удаления на FK-ограничениях

Hibernate DDL установлен в `validate` — все изменения схемы только через Flyway-миграции.

**Конфликт Flyway**: если тесты падают с "Found more than one migration with version N", запустить `./gradlew clean` для очистки устаревших артефактов сборки, затем пересобрать.

### Соглашения по именованию индексов

| Тип               | Паттерн                    |
|-------------------|----------------------------|
| Индекс            | `idx_<table>_<column>`     |
| Уникальное огр-е  | `uq_<table>_<col1>_<col2>` |
| Внешний ключ      | `fk_<table>_<column>`      |

Частичные индексы (`WHERE col IS NOT NULL`) обязательны для nullable-колонок во избежание срабатывания pg-index-health.

## Тестирование

Все интеграционные тесты наследуют `BaseIT`:

- `@SpringBootTest(webEnvironment = RANDOM_PORT)`
- `@ActiveProfiles("test")`
- Testcontainers `PostgreSQLContainer` с `@ServiceConnection`
- `TestRestTemplate` для HTTP-вызовов
- `@MockBean GitLabApiClient` — GitLab всегда мокируется в IT
- `@AfterEach cleanUpDatabase()` — очищает все таблицы; добавлять новые таблицы сюда при создании миграций

Именование тестов: `*Test` (не `*IT`), даже для интеграционных тестов.

**`DatabaseStructureTest`** — проверки pg-index-health через `pg-index-health-test-starter:0.20.3` (последняя версия, совместимая со Spring Boot 3.3.x). Запускает только статические проверки; пропускает `flyway_schema_history` через `SkipFlywayTablesPredicate.ofDefault()`.

**OAuth2 в тестах**: `application-test.yml` предоставляет фиктивные GitHub-credentials (`test-github-client-id` / `test-github-client-secret`), чтобы контекст приложения запускался без реальной OAuth2-конфигурации.

## Ключевая конфигурация

`src/main/resources/application.yml` — центральная конфигурация.
`src/test/resources/application-test.yml` — переопределения для тестов.

Переменные окружения:

- `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` — подключение к базе данных
- `API_TOKEN` — Bearer-токен для аутентификации REST API (по умолчанию: `changeme-in-production`)
- `GITHUB_CLIENT_ID`, `GITHUB_CLIENT_SECRET` — credentials GitHub OAuth2 App для входа в веб-UI

## Observability

Spring Boot Actuator + Micrometer настроены. Доступные эндпоинты:

- `GET /actuator/health` — полный health: БД, диск, liveness, readiness. `show-details: always`.
- `GET /actuator/health/liveness` — liveness probe
- `GET /actuator/health/readiness` — readiness probe
- `GET /actuator/metrics` — список метрик Micrometer
- `GET /actuator/prometheus` — scrape-эндпоинт для Prometheus

Все метрики содержат тег `application=gitlab-analytics` (задаётся через `management.metrics.tags.application`). Включённые группы метрик: JVM, process, system, HTTP server requests, HikariCP.

Эндпоинты `/actuator/**` открыты по HTTP без дополнительной аутентификации (допустимо для внутреннего/локального использования). В продакшне — разместить за внутренней сетью или добавить защиту.

### Стек мониторинга (локально: `docker-compose.monitoring.yml`, прод: `docker-compose.prod.yml`)

| Сервис     | Порт | Описание |
|------------|------|----------|
| Prometheus | 9090 | scrape-ит `host.docker.internal:8080/actuator/prometheus` (локально) или `app:8080` (прод) |
| Grafana    | 3000 | admin/admin — дашборды auto-provisioned из `monitoring/grafana/dashboards/` |
| Loki       | 3100 | хранилище логов — заполняется Promtail, читающим логи Docker-контейнеров |
| Promtail   | —    | читает `/var/lib/docker/containers` + Docker socket, отправляет логи в Loki |
| Portainer  | 9000 | Docker UI — все контейнеры (running + stopped), логи, перезапуск. При первом входе создаётся admin-пароль (≥12 символов). |

**cAdvisor удалён** — Portainer закрывает задачу видимости контейнеров. Метрики контейнеров на уровне Prometheus не собираются (node-exporter покрывает метрики хоста в продакшне).

## Зависимости

Spring Boot 3.3.4, Java 21, Gradle Kotlin DSL.

Зависимости вне BOM (версии фиксировать вручную):

- `spring-boot-starter-oauth2-client` — вход через GitHub OAuth2 для веб-UI
- `spring-boot-starter-thymeleaf` + `thymeleaf-extras-springsecurity6` — серверные шаблоны
- `micrometer-registry-prometheus` — экспорт метрик в Prometheus (`runtimeOnly`)
- `springdoc-openapi-starter-webmvc-ui:2.6.0`
- `pg-index-health-test-starter:0.20.3`
- `spotbugs-annotations:4.8.6`
