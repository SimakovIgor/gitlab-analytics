# Промт: Миграция GitLab Analytics на мультитенантную SaaS-архитектуру

## Контекст проекта

Spring Boot 3.3.4, Java 21, PostgreSQL, Flyway, JPA/Hibernate, Thymeleaf, Chart.js.
OAuth2-логин через GitHub. Два security-chain: API (Bearer token) и Web (OAuth2 сессии).

**Текущее состояние**: 100% однотенантное приложение. Все пользователи видят общие данные.
**Цель**: SaaS с workspace-изоляцией. Каждая команда = свой workspace с полной изоляцией данных.

---

## Целевая архитектура

```
GitHub OAuth Login
       ↓
   AppUser (github_username, name, avatar_url)
       ↓
WorkspaceMember (role: OWNER | MEMBER)
       ↓
   Workspace (id, name, slug, plan)
       ↓
  ┌────────────────────────────────────┐
  │  GitSource → TrackedProject        │
  │  TrackedUser → TrackedUserAlias    │
  │  MergeRequest → (commits/notes/..) │
  │  MetricSnapshot                    │
  │  SyncJob                           │
  └────────────────────────────────────┘
```

Scheduled jobs (NightlySyncScheduler, SnapshotService) итерируются по workspace-ам.
API-токен привязан к workspace, не глобальный.

---

## Шаг 1 — Flyway миграция V6 (новые таблицы + workspace_id FK)

Создать файл `src/main/resources/db/migration/V6__add_workspace_multitenancy.sql`:

```sql
-- 1. Таблица приложения-пользователей (отдельно от TrackedUser)
CREATE TABLE app_user
(
    id            BIGSERIAL PRIMARY KEY,
    github_id     BIGINT       NOT NULL UNIQUE,
    github_login  VARCHAR(255) NOT NULL UNIQUE,
    name          VARCHAR(255),
    avatar_url    VARCHAR(1024),
    email         VARCHAR(255),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_login_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- 2. Workspace (tenant)
CREATE TABLE workspace
(
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(255) NOT NULL,
    slug       VARCHAR(100) NOT NULL UNIQUE,         -- для URL: analytics.app/ws/my-team
    owner_id   BIGINT       NOT NULL REFERENCES app_user (id),
    plan       VARCHAR(50)  NOT NULL DEFAULT 'FREE', -- FREE | PRO | ENTERPRISE
    api_token  VARCHAR(255) NOT NULL UNIQUE,         -- sha256 hex, заменяет глобальный токен
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_workspace_slug ON workspace (slug);
CREATE INDEX idx_workspace_owner ON workspace (owner_id);

-- 3. Участники workspace
CREATE TABLE workspace_member
(
    id           BIGSERIAL PRIMARY KEY,
    workspace_id BIGINT      NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,
    app_user_id  BIGINT      NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    role         VARCHAR(50) NOT NULL DEFAULT 'MEMBER', -- OWNER | MEMBER
    invited_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_workspace_member UNIQUE (workspace_id, app_user_id)
);
CREATE INDEX idx_workspace_member_user ON workspace_member (app_user_id);

-- 4. Добавить workspace_id в tenant-специфичные таблицы
ALTER TABLE git_source
    ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 0
        REFERENCES workspace (id) ON DELETE CASCADE;
ALTER TABLE tracked_project
    ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 0
        REFERENCES workspace (id) ON DELETE CASCADE;
ALTER TABLE tracked_user
    ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 0
        REFERENCES workspace (id) ON DELETE CASCADE;
ALTER TABLE sync_job
    ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 0
        REFERENCES workspace (id) ON DELETE CASCADE;
ALTER TABLE metric_snapshot
    ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 0
        REFERENCES workspace (id) ON DELETE CASCADE;

-- 5. Индексы на workspace_id (обязательны для производительности)
CREATE INDEX idx_git_source_workspace ON git_source (workspace_id);
CREATE INDEX idx_tracked_project_workspace ON tracked_project (workspace_id);
CREATE INDEX idx_tracked_user_workspace ON tracked_user (workspace_id);
CREATE INDEX idx_sync_job_workspace ON sync_job (workspace_id);
CREATE INDEX idx_metric_snapshot_workspace ON metric_snapshot (workspace_id);

-- 6. UNIQUE constraints переделать с учётом workspace
-- Было: UNIQUE(git_source_id, gitlab_project_id)
-- Стало: UNIQUE(workspace_id, git_source_id, gitlab_project_id)
ALTER TABLE tracked_project
    DROP CONSTRAINT uq_tracked_project;
ALTER TABLE tracked_project
    ADD CONSTRAINT uq_tracked_project
        UNIQUE (workspace_id, git_source_id, gitlab_project_id);

-- metric_snapshot: было UNIQUE(tracked_user_id, snapshot_date)
-- стало: UNIQUE(workspace_id, tracked_user_id, snapshot_date)
ALTER TABLE metric_snapshot
    DROP CONSTRAINT uq_metric_snapshot_user_date;
ALTER TABLE metric_snapshot
    ADD CONSTRAINT uq_metric_snapshot_user_date
        UNIQUE (workspace_id, tracked_user_id, snapshot_date);
```

**ВАЖНО**: `DEFAULT 0` на FK — временный placeholder, нужно data-migration в том же скрипте
если в БД уже есть данные (создать workspace_id=1 и заполнить все строки).

---

## Шаг 2 — JPA-сущности

### Новые сущности

**`domain/model/AppUser.java`**

```java

@Entity
@Table(name = "app_user")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AppUser {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true)
    private Long githubId;
    @Column(nullable = false, unique = true)
    private String githubLogin;
    private String name;
    private String avatarUrl;
    private String email;
    @CreationTimestamp
    private Instant createdAt;
    private Instant lastLoginAt;
}
```

**`domain/model/Workspace.java`**

```java

@Entity
@Table(name = "workspace")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Workspace {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private String name;
    @Column(nullable = false, unique = true)
    private String slug;
    @Column(nullable = false)
    private Long ownerId;
    @Column(nullable = false)
    private String plan;   // FREE | PRO
    @Column(nullable = false, unique = true)
    private String apiToken; // sha256 hex
    @CreationTimestamp
    private Instant createdAt;
    @UpdateTimestamp
    private Instant updatedAt;
}
```

**`domain/model/WorkspaceMember.java`**

```java

@Entity
@Table(name = "workspace_member")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkspaceMember {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long workspaceId;
    @Column(nullable = false)
    private Long appUserId;
    @Column(nullable = false)
    private String role; // OWNER | MEMBER
    @CreationTimestamp
    private Instant invitedAt;
}
```

**`domain/model/enums/WorkspaceRole.java`** — `OWNER, MEMBER`

### Изменить существующие сущности

Добавить `private Long workspaceId;` (с `@Column(nullable = false)`) в:

- `GitSource`
- `TrackedProject`
- `TrackedUser`
- `SyncJob`
- `MetricSnapshot`

---

## Шаг 3 — Репозитории

### Новые репозитории

**`AppUserRepository`**:

```java
Optional<AppUser> findByGithubId(Long githubId);

Optional<AppUser> findByGithubLogin(String githubLogin);
```

**`WorkspaceRepository`**:

```java
Optional<Workspace> findBySlug(String slug);

Optional<Workspace> findByApiToken(String apiToken);

List<Workspace> findByOwnerId(Long ownerId);
```

**`WorkspaceMemberRepository`**:

```java
Optional<WorkspaceMember> findByWorkspaceIdAndAppUserId(Long workspaceId, Long appUserId);

List<WorkspaceMember> findByAppUserId(Long appUserId);

boolean existsByWorkspaceIdAndAppUserId(Long workspaceId, Long appUserId);
```

### Изменить существующие репозитории

Во всех репозиториях добавить `workspaceId` в каждый метод:

**`GitSourceRepository`**:

```java
List<GitSource> findAllByWorkspaceId(Long workspaceId);

Optional<GitSource> findByIdAndWorkspaceId(Long id, Long workspaceId);
```

**`TrackedProjectRepository`**:

```java
// Было:
List<TrackedProject> findAllByEnabledTrue();

Optional<TrackedProject> findByGitSourceIdAndGitlabProjectId(Long, Long);

Optional<TrackedProject> findFirstByGitSourceId(Long);

// Стало:
List<TrackedProject> findAllByWorkspaceIdAndEnabledTrue(Long workspaceId);

List<TrackedProject> findAllByWorkspaceId(Long workspaceId);

Optional<TrackedProject> findByWorkspaceIdAndGitSourceIdAndGitlabProjectId(Long, Long, Long);

Optional<TrackedProject> findFirstByWorkspaceIdAndGitSourceId(Long, Long);

Optional<TrackedProject> findByIdAndWorkspaceId(Long id, Long workspaceId);
```

**`TrackedUserRepository`**:

```java
// Было:
List<TrackedUser> findAllByEnabledTrue();

// Стало:
List<TrackedUser> findAllByWorkspaceId(Long workspaceId);

List<TrackedUser> findAllByWorkspaceIdAndEnabledTrue(Long workspaceId);

Optional<TrackedUser> findByIdAndWorkspaceId(Long id, Long workspaceId);
```

**`SyncJobRepository`**:

```java
// Добавить:
List<SyncJob> findByWorkspaceIdAndStatusOrderByStartedAtDesc(Long workspaceId, SyncStatus status);

List<SyncJob> findTop30ByWorkspaceIdOrderByStartedAtDesc(Long workspaceId);

boolean existsByWorkspaceIdAndStatus(Long workspaceId, SyncStatus status);
// findByStatusAndStartedAtBefore — оставить без workspace (для StaleJobWatchdog)
```

**`MetricSnapshotRepository`**:

```java
// Было:
Optional<MetricSnapshot> findByTrackedUserIdAndSnapshotDate(Long, LocalDate);

List<MetricSnapshot> findHistory(List<Long> userIds, LocalDate from, LocalDate to);

// Стало:
Optional<MetricSnapshot> findByWorkspaceIdAndTrackedUserIdAndSnapshotDate(Long, Long, LocalDate);
// findHistory — добавить WHERE s.workspaceId = :workspaceId в @Query
```

**`MergeRequestCommitRepository`** — изменить `findContributorRows()`:

```sql
-- Добавить фильтр по workspace через JOIN:
WHERE tp.workspace_id = :workspaceId AND mrc.author_email IS NOT NULL ...
```

**`MergeRequestRepository`** — изменить `findAuthorGitlabUserIdByCommitEmail()`:

```sql
-- Добавить JOIN tracked_project tp и WHERE tp.workspace_id = :workspaceId
```

---

## Шаг 4 — WorkspaceContext (ключевой компонент)

Создать `web/WorkspaceContext.java` — резолвер текущего workspace из запроса:

```java

@Component
@RequiredArgsConstructor
public class WorkspaceContext {
    private final WorkspaceMemberRepository memberRepository;
    private final WorkspaceRepository workspaceRepository;
    private final AppUserRepository appUserRepository;

    // Для Web (OAuth2) — извлекает workspace из сессии пользователя
    public Long resolveWorkspaceId(OAuth2AuthenticationToken auth) {
        // 1. Найти AppUser по github_id из auth
        // 2. Найти WorkspaceMember по appUserId (первый workspace или из сессии)
        // 3. Вернуть workspaceId
        // Если нет workspace → пользователь попадает на /onboarding
    }

    // Для API (Bearer token) — по токену находит workspace
    public Long resolveWorkspaceIdFromToken(String token) {
        return workspaceRepository.findByApiToken(token)
            .map(Workspace::getId)
            .orElseThrow(() -> new UnauthorizedException("Invalid token"));
    }

    // Хелпер для проверки что текущий пользователь — член workspace
    public Workspace requireMembership(Long appUserId, Long workspaceId) {
        if (!memberRepository.existsByWorkspaceIdAndAppUserId(workspaceId, appUserId)) {
            throw new ForbiddenException("Not a member of this workspace");
        }
        return workspaceRepository.findById(workspaceId).orElseThrow();
    }
}
```

---

## Шаг 5 — OAuth2UserService (замена OAuth2UserResolver)

Создать `security/AppUserOAuth2Service.java` — при каждом логине создаёт/обновляет AppUser:

```java

@Service
@RequiredArgsConstructor
public class AppUserOAuth2Service implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {
    private final AppUserRepository appUserRepository;

    @Override
    public OAuth2User loadUser(OAuth2UserRequest request) {
        OAuth2User oauthUser = new DefaultOAuth2UserService().loadUser(request);
        Map<String, Object> attrs = oauthUser.getAttributes();

        Long githubId = ((Number) attrs.get("id")).longValue();
        String login = (String) attrs.get("login");
        String name = (String) attrs.get("name");
        String avatarUrl = (String) attrs.get("avatar_url");

        AppUser user = appUserRepository.findByGithubId(githubId).orElseGet(AppUser::new);
        user.setGithubId(githubId);
        user.setGithubLogin(login);
        user.setName(name);
        user.setAvatarUrl(avatarUrl);
        user.setLastLoginAt(Instant.now());
        appUserRepository.save(user);

        return oauthUser;  // Сохраняем стандартный OAuth2User для Spring Security
    }
}
```

Подключить в `SecurityConfig`:

```java
.oauth2Login(oauth2 ->oauth2
    .

userInfoEndpoint(ui ->ui.

userService(appUserOAuth2Service))
    .

successHandler(workspaceAwareSuccessHandler)  // → /onboarding или /report
)
```

---

## Шаг 6 — WorkspaceService (создание/управление workspace)

Создать `web/WorkspaceService.java`:

```java

@Service
@RequiredArgsConstructor
@Transactional
public class WorkspaceService {
    // createWorkspace(AppUser owner, String name) → Workspace
    //   - генерирует slug из name (slugify)
    //   - генерирует apiToken = SHA-256(UUID.randomUUID())
    //   - сохраняет Workspace
    //   - создаёт WorkspaceMember(workspaceId, owner.id, OWNER)

    // inviteMember(Long workspaceId, String githubLogin) → WorkspaceMember

    // removeMember(Long workspaceId, Long appUserId)

    // getWorkspacesForUser(Long appUserId) → List<Workspace>

    // rotateApiToken(Long workspaceId) → String (новый токен)
}
```

---

## Шаг 7 — Изменения в сервисном слое

### BearerTokenAuthFilter — замена глобального токена

```java
// Было: сравнивает с app.security.api-token (один на всё)
// Стало: ищет workspace по токену
Optional<Workspace> workspace = workspaceRepository.findByApiToken(token);
if(workspace.

isPresent()){
WorkspaceAuthentication auth = new WorkspaceAuthentication(workspace.get().getId(), "api-client");
    SecurityContextHolder.

getContext().

setAuthentication(auth);
}
```

Создать `security/WorkspaceAuthentication.java` — `UsernamePasswordAuthenticationToken` с `workspaceId` в details.

### SettingsService — добавить workspaceId везде

```java
// Каждый метод получает workspaceId (из контроллера через WorkspaceContext):
public GitSource createSource(Long workspaceId, CreateGitSourceRequest request) {
    GitSource source = ...;
    source.setWorkspaceId(workspaceId);
    return gitSourceRepository.save(source);
}

public CreatedProjectResult createProject(Long workspaceId, CreateTrackedProjectRequest request) {
    TrackedProject project = ...;
    project.setWorkspaceId(workspaceId);
    ...
}

// Аналогично для: deleteSource, createProject, deleteProject, createUser, createUsersBulk, etc.
// Все findAll() → findAllByWorkspaceId(workspaceId)
```

### ReportViewService — добавить workspaceId

```java
public ReportPageData buildReportPage(Long workspaceId, String period, ...) {
    List<GitSource> sources = gitSourceRepository.findAllByWorkspaceId(workspaceId);
    List<TrackedProject> allProjects = trackedProjectRepository.findAllByWorkspaceId(workspaceId);
    List<TrackedUser> allUsers = trackedUserRepository.findAllByWorkspaceId(workspaceId);
    ...
}
```

### SnapshotService — workspaceId в resolveUserIds/resolveProjectIds

```java
// Было:
private List<Long> resolveUserIds(List<Long> requested) {
    return trackedUserRepository.findAllByEnabledTrue()...;
}

// Стало:
private List<Long> resolveUserIds(List<Long> requested, Long workspaceId) {
    if (requested != null && !requested.isEmpty()) return requested;
    return trackedUserRepository.findAllByWorkspaceIdAndEnabledTrue(workspaceId)...;
}
```

`RunSnapshotRequest` расширить полем `workspaceId`.

### NightlySyncScheduler — итерация по workspace-ам

```java

@Scheduled(cron = "${app.sync.cron:0 0 3 * * *}")
public void runNightlySync() {
    List<Workspace> workspaces = workspaceRepository.findAll();
    for (Workspace ws : workspaces) {
        try {
            syncWorkspace(ws);
        } catch (Exception e) {
            log.error("Nightly sync failed for workspace {}: {}", ws.getId(), e.getMessage());
        }
    }
}

private void syncWorkspace(Workspace ws) {
    List<Long> projectIds = trackedProjectRepository
        .findAllByWorkspaceIdAndEnabledTrue(ws.getId())
        .stream().map(TrackedProject::getId).toList();
    if (projectIds.isEmpty()) return;

    boolean alreadyRunning = syncJobRepository
        .existsByWorkspaceIdAndStatus(ws.getId(), SyncStatus.STARTED);
    if (alreadyRunning) {
        log.warn(...);
        return;
    }

    ManualSyncRequest request = new ManualSyncRequest(projectIds, dateFrom, dateTo, true, true, true);
    var job = syncJobService.create(ws.getId(), request);
    syncOrchestrator.orchestrateAsync(job.getId(), request);
}
```

`SyncJobService.create()` — добавить параметр `workspaceId`.

---

## Шаг 8 — Web-контроллеры

### Новые endpoint-ы

**`OnboardingController`** (`/onboarding`):

- `GET /onboarding` → страница создания workspace (если у пользователя нет workspace)
- `POST /onboarding` → `WorkspaceService.createWorkspace()` → редирект на `/report`

**`WorkspaceController`** (`/workspace`):

- `GET /workspace/settings` → управление workspace, участники, план, API-токен
- `POST /workspace/members` → invite by github login
- `DELETE /workspace/members/{id}` → remove member
- `POST /workspace/rotate-token` → сгенерировать новый API-токен

### Изменить существующие контроллеры

**WebController**, **SettingsController**, **DoraController**, **HistoryController** — каждый должен:

1. Извлекать `workspaceId` через `WorkspaceContext.resolveWorkspaceId(auth)`
2. Передавать `workspaceId` во все вызовы сервисов

Пример для `WebController`:

```java

@GetMapping("/report")
public String report(OAuth2AuthenticationToken auth, ...,Model model) {
    Long workspaceId = workspaceContext.resolveWorkspaceId(auth);
    ReportPageData data = reportViewService.buildReportPage(workspaceId, period, projectIds, showInactive);
    ...
}
```

**API-контроллеры** извлекают `workspaceId` из `SecurityContext`:

```java
// Хелпер:
private Long currentWorkspaceId() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    return ((WorkspaceAuthentication) auth).getWorkspaceId();
}
```

### Success handler после OAuth2 логина

```java

@Component
@RequiredArgsConstructor
public class WorkspaceAwareSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {
    private final AppUserRepository appUserRepository;
    private final WorkspaceMemberRepository memberRepository;

    @Override
    public void onAuthenticationSuccess(...) {
        Long githubId = extractGithubId(authentication);
        AppUser user = appUserRepository.findByGithubId(githubId).orElseThrow();
        boolean hasWorkspace = !memberRepository.findByAppUserId(user.getId()).isEmpty();
        String target = hasWorkspace ? "/report" : "/onboarding";
        response.sendRedirect(target);
    }
}
```

---

## Шаг 9 — AppProperties (убрать глобальный api-token)

```java
// Убрать из AppProperties.Security:
// @NotBlank String apiToken

// Теперь токен хранится в workspace.api_token
// Оставить только для обратной совместимости или убрать полностью
```

Обновить `application.yml` — убрать `app.security.api-token` (или оставить для admin-доступа).

---

## Шаг 10 — Новые Thymeleaf шаблоны

### `/onboarding.html` — страница создания workspace

```html
<!-- Форма: name workspace, кнопка "Создать" -->
<!-- После создания → /report -->
```

### Изменить `report.html`, `dora.html`, `settings.html`

В хедер добавить:

```html
<!-- Переключатель workspace (если у пользователя их несколько) -->
<div class="workspace-selector" th:if="${workspaces.size() > 1}">
    <span th:text="${currentWorkspace.name}">My Team</span>
    <!-- dropdown -->
</div>
```

В `settings.html` добавить секцию Workspace:

- Имя workspace
- API-токен (masked, кнопка "Показать", "Сгенерировать новый")
- Участники (список, invite по github login, удаление)
- Тарифный план (FREE / PRO)

---

## Шаг 11 — Тесты

### `BaseIT` — добавить workspace setup

```java
// В BaseIT.setUp():
protected Long workspaceId;
protected Long appUserId;

@BeforeEach
void setUpWorkspace() {
    AppUser owner = appUserRepository.save(AppUser.builder()
        .githubId(12345L).githubLogin("test-user").name("Test User").build());
    appUserId = owner.getId();

    Workspace ws = workspaceRepository.save(Workspace.builder()
        .name("Test Workspace").slug("test-ws")
        .ownerId(owner.getId()).plan("FREE")
        .apiToken("test-api-token").build());
    workspaceId = ws.getId();

    workspaceMemberRepository.save(WorkspaceMember.builder()
        .workspaceId(workspaceId).appUserId(appUserId).role("OWNER").build());
}
```

Все тесты, которые сейчас создают `GitSource`, `TrackedProject`, `TrackedUser` — добавить `.workspaceId(workspaceId)`.

Все HTTP-запросы к API — добавить заголовок `Authorization: Bearer test-api-token`
(в `authHeaders()` метод `BaseIT`).

### Новые тест-классы

- `WorkspaceServiceTest` — создание workspace, invite, remove
- `WorkspaceIsolationTest` — проверка что данные ws1 не видны из ws2
- `OnboardingControllerTest` — flow создания workspace через UI

---

## Порядок выполнения (критически важен)

```
1. V6 миграция (SQL)
2. Новые entity: AppUser, Workspace, WorkspaceMember
3. Новые репозитории: AppUser-, Workspace-, WorkspaceMember-Repository
4. Изменить entity: добавить workspaceId в GitSource, TrackedProject, TrackedUser, SyncJob, MetricSnapshot
5. Изменить репозитории: добавить workspaceId в методы (по одному файлу, проверяя компиляцию)
6. WorkspaceContext, WorkspaceAuthentication
7. BearerTokenAuthFilter — заменить логику токена
8. AppUserOAuth2Service + WorkspaceAwareSuccessHandler → SecurityConfig
9. WorkspaceService
10. SettingsService — добавить workspaceId
11. ReportViewService, HistoryViewService, DoraService — добавить workspaceId
12. SnapshotService — добавить workspaceId
13. NightlySyncScheduler — итерация по workspace
14. SyncJobService.create() — добавить workspaceId
15. Контроллеры (web + api)
16. OnboardingController + шаблон
17. WorkspaceController + шаблон настроек
18. Обновить Thymeleaf шаблоны (header)
19. BaseIT обновить
20. Все тест-файлы — добавить workspaceId
21. Новые тесты
22. DatabaseStructureTest — добавить новые таблицы в ignore list если нужно
```

---

## Файлы проекта для справки

**Структура пакетов:**

```
io.simakov.analytics
├── api/
│   ├── controller/   ReportController, SyncController, TrackedUserController, SnapshotController
│   ├── dto/request/  ManualSyncRequest, RunSnapshotRequest, CreateTrackedUserRequest, CreateTrackedProjectRequest
│   ├── dto/response/ RunSnapshotResponse, SyncJobResponse, SnapshotHistoryResponse
│   └── exception/    GlobalExceptionHandler, ResourceNotFoundException
├── config/           AppProperties, SecurityConfig, AsyncConfig, WebClientConfig
├── domain/
│   ├── model/        GitSource, TrackedProject, TrackedUser, TrackedUserAlias,
│   │                 MergeRequest, MergeRequestCommit, MergeRequestNote,
│   │                 MergeRequestDiscussion, MergeRequestApproval,
│   │                 MetricSnapshot, SyncJob
│   ├── model/enums/  MrState, SyncStatus, PeriodType, ScopeType, TimeGroupBy, WorkspaceRole(NEW)
│   └── repository/   (все репозитории выше)
├── gitlab/           GitLabApiClient, GitLabMapper, dto/*
├── metrics/          MetricCalculationService, model/UserMetrics, model/Metric(enum)
├── security/         BearerTokenAuthFilter → ИЗМЕНИТЬ
├── snapshot/         SnapshotService, SnapshotHistoryService
├── sync/             SyncOrchestrator, SyncJobService, NightlySyncScheduler, StaleJobWatchdog
├── util/             DateTimeUtils
└── web/
    ├── controller/   WebController, SettingsController, DoraController, HistoryController
    ├── dto/          ReportPageData, HistoryPageData, SettingsPageData, MrSummaryDto, ...
    ├── ContributorDiscoveryService, UserAliasService, ReportViewService
    ├── SettingsService, HistoryViewService, DoraService
    └── OAuth2UserResolver → ЗАМЕНИТЬ на AppUserOAuth2Service
```

**Templates:** `src/main/resources/templates/` — report.html, dora.html, settings.html, history.html, login.html
**Static:** `src/main/resources/static/css/analytics.css`
**Migrations:** `src/main/resources/db/migration/V1..V6`

---

## Что НЕ менять

- `MergeRequest`, `MergeRequestCommit`, `MergeRequestNote`, `MergeRequestDiscussion`, `MergeRequestApproval`
  — они фильтруются через `trackedProjectId`, который уже принадлежит workspace.
  Если запрос приходит с `projectIds` принадлежащими workspace — утечки нет.
- `GitLabApiClient` — без изменений, он stateless.
- `MetricCalculationService` — без изменений, принимает projectIds/userIds.
- `EncryptionService` — без изменений.
- `StaleJobWatchdog` — глобальный, без изменений (работает со всеми статусами).
- `TrackedUserAlias` — нет workspace_id, доступ через trackedUserId который уже workspace-скопирован.

---

## Доп. замечания

1. **Slug генерация**: `name.toLowerCase().replaceAll("[^a-z0-9]", "-").replaceAll("-+", "-")`
2. **API-токен**: `HexFormat.of().formatHex(sha256(UUID.randomUUID().toString().getBytes()))`
3. **Cascade delete**: при удалении workspace CASCADE удалит GitSource → TrackedProject → MergeRequest → всё дочернее
4. **Plan field**: начать с `FREE`, позже добавить billing. FREE ограничение: например 3 проекта, 10 пользователей.
5. **Invite flow MVP**: owner вводит github login → `WorkspaceMember` создаётся, при следующем логине этого пользователя он попадает в workspace.
6. **Multi-workspace**: один AppUser может быть членом нескольких workspace. Активный workspace хранится в сессии.
7. **DatabaseStructureTest**: после добавления новых таблиц убедиться что все FK имеют индексы (иначе тест провалится).
