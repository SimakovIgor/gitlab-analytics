# dev-start

Запускает полное локальное окружение: PostgreSQL + Prometheus + Grafana (Docker) → Spring Boot.

## Флаги

| Флаг              | Описание                                                                |
|-------------------|-------------------------------------------------------------------------|
| _(без флагов)_    | PostgreSQL + Prometheus + Grafana + bootRun со статическим анализом     |
| `--no-monitoring` | Только PostgreSQL + приложение                                          |
| `--fast`          | Пропустить checkstyle/pmd/spotbugs (быстрее для итеративной разработки) |
| `--jar`           | Запустить собранный jar вместо bootRun (быстрее, если jar уже есть)     |
| `--fast --jar`    | Комбинация: собрать без проверок, запустить jar                         |

## Выполни

```bash
./scripts/dev-start.sh
```

Или с флагами, если пользователь уточнил режим запуска.

## После запуска сообщи пользователю

Окружение поднято:

- **App**: http://localhost:8080
- **Swagger**: http://localhost:8080/swagger-ui.html
- **Health**: http://localhost:8080/actuator/health
- **Prometheus scrape**: http://localhost:8080/actuator/prometheus
- **Prometheus UI**: http://localhost:9090
- **Grafana**: http://localhost:3000 (admin / admin)
    - Dashboard **JVM / Spring Boot 3.x Statistics** — CPU, memory, GC, threads, HTTP, HikariCP
    - Dashboard **Application** — active/completed/failed sync jobs, HTTP latency p99 по endpoint

## Если переменные не заданы

Скрипт сам сообщит что не хватает. Подсказать пользователю:

```bash
cp .env.example .env.local  # и заполнить GITHUB_CLIENT_ID / GITHUB_CLIENT_SECRET
```
