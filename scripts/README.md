# Scripts

Все скрипты запускаются из корня проекта. Перед первым использованием убедитесь, что они исполняемы:

```bash
chmod +x scripts/*.sh
```

---

## Предварительные условия

### Локальная разработка
- [Docker Desktop](https://www.docker.com/products/docker-desktop/) — используется как Docker-движок
- Файл `.env.local` в корне проекта (скопируйте из `.env.example`)

### Работа с удалённым сервером
- Файл `.env.prod` в корне проекта (скопируйте из `.env.prod.example`)
- SSH-доступ к серверу без пароля (ключ в `~/.ssh/`)
- Сервер подготовлен через `scripts/setup-server.sh`

---

## dev-start.sh — запуск локального окружения

Поднимает PostgreSQL (+ опционально Prometheus/Grafana/Loki) и запускает Spring Boot.

```bash
./scripts/dev-start.sh                  # полный запуск: БД + мониторинг + приложение
./scripts/dev-start.sh --no-monitoring  # только PostgreSQL + приложение
./scripts/dev-start.sh --fast           # пропустить Checkstyle/PMD/SpotBugs
./scripts/dev-start.sh --jar            # запустить собранный jar (быстрее, чем bootRun)
./scripts/dev-start.sh --fast --jar     # флаги комбинируются
```

После старта доступно:
- http://localhost:8080 — приложение
- http://localhost:8080/swagger-ui.html — Swagger UI
- http://localhost:8080/actuator/health — health
- http://localhost:3000 — Grafana (admin / admin)
- http://localhost:9090 — Prometheus

---

## deploy.sh — деплой на сервер

Собирает JAR, синхронизирует файлы по rsync, запускает docker compose на сервере.

```bash
./scripts/deploy.sh
```

Требует `.env.prod` с заполненными `SERVER_IP` и `SERVER_USER`.

**Что происходит:**
1. `./gradlew bootJar` — сборка JAR
2. `rsync` — синхронизация проекта на сервер (исключая `.git`, `.gradle`, `build/tmp` и т.д.)
3. `scp .env.prod` → сервер как `.env`
4. `docker compose up -d --build` — пересборка и запуск контейнеров
5. Печатает URL приложения, Grafana, Prometheus

---

## restart-remote.sh — рестарт на сервере

```bash
./scripts/restart-remote.sh           # рестарт только контейнера app
./scripts/restart-remote.sh --all     # полный рестарт всего стека (down → up)
./scripts/restart-remote.sh --status  # показать текущий статус контейнеров (docker compose ps)
```

---

## db-reset-local.sh — сброс локальной БД

Дропает все таблицы приложения и очищает `flyway_schema_history`. При следующем старте Flyway накатит миграции заново.

```bash
./scripts/db-reset-local.sh           # только сброс БД
./scripts/db-reset-local.sh --full    # сброс + пересборка (без тестов)
```

После сброса запустите `./scripts/dev-start.sh` — приложение стартует с чистой БД.

---

## db-reset-remote.sh — сброс удалённой БД

То же самое на сервере. **Необратимо** — требует ввести `yes` для подтверждения.

```bash
./scripts/db-reset-remote.sh          # сброс БД на сервере
./scripts/db-reset-remote.sh --full   # сброс + рестарт приложения после
```

---

## logs.sh — просмотр логов

```bash
./scripts/logs.sh                        # локальные логи контейнера app (follow)
./scripts/logs.sh -n 500                 # последние 500 строк + follow
./scripts/logs.sh --service postgres     # логи другого локального контейнера
./scripts/logs.sh --service grafana      # логи Grafana
./scripts/logs.sh --remote               # логи app с удалённого сервера
./scripts/logs.sh --remote -n 200        # последние 200 строк с сервера + follow
./scripts/logs.sh --remote --service postgres  # логи postgres на сервере
```

Прервать: `Ctrl+C`.

---

## health.sh — проверка состояния приложения

Проверяет `/actuator/health`, liveness, readiness и prometheus scrape endpoint.

```bash
./scripts/health.sh                # локальная проверка
./scripts/health.sh --remote       # проверка на удалённом сервере
./scripts/health.sh --watch        # обновлять каждые 5 секунд (локально)
./scripts/health.sh --remote --watch  # наблюдение за сервером
```

---

## setup-server.sh — первичная настройка сервера

Устанавливает Docker и Docker Compose Plugin на чистый Ubuntu-сервер. Запускается **один раз** при подготовке сервера.

```bash
# Скопировать на сервер и выполнить под root:
scp scripts/setup-server.sh root@<SERVER_IP>:/tmp/
ssh root@<SERVER_IP> bash /tmp/setup-server.sh
```

---

## Типовые сценарии

### Первый запуск локально
```bash
cp .env.example .env.local     # заполнить GITHUB_CLIENT_ID и GITHUB_CLIENT_SECRET
./scripts/dev-start.sh
```

### Первый деплой на сервер
```bash
cp .env.prod.example .env.prod  # заполнить SERVER_IP, SERVER_USER и остальные переменные
ssh root@<SERVER_IP> bash /tmp/setup-server.sh  # один раз
./scripts/deploy.sh
```

### Что-то пошло не так на сервере
```bash
./scripts/health.sh --remote          # посмотреть что не UP
./scripts/logs.sh --remote -n 300     # найти причину в логах
./scripts/restart-remote.sh           # перезапустить
```

### Чистый пересброс для отладки
```bash
# Локально:
./scripts/db-reset-local.sh --full && ./scripts/dev-start.sh

# Удалённо:
./scripts/db-reset-remote.sh --full
```
