package io.simakov.analytics.dora.model;

/**
 * Единственный источник истины для четырёх DORA-метрик (DevOps Research and Assessment).
 *
 * <p>Каждая константа хранит:
 * <ul>
 *   <li>идентификатор метрики — {@link #key()}, {@link #label()}, {@link #unit()}</li>
 *   <li>статус реализации — {@link #status()} (AVAILABLE / COMING_SOON)</li>
 *   <li>формулу расчёта — {@link #description()}</li>
 *   <li>пороги рейтинга и направление — {@link #computeRating(Double)}</li>
 * </ul>
 *
 * <hr>
 * <h2>Методология расчёта метрик</h2>
 *
 * <h3>1. PR CYCLE TIME — прокси для Lead Time for Changes</h3>
 * <pre>
 *   Статус: AVAILABLE
 *   Источник: таблица merge_request (merged_at_gitlab, created_at_gitlab)
 *   Формула: PERCENTILE_CONT(0.5) WITHIN GROUP (
 *              ORDER BY EXTRACT(EPOCH FROM (merged_at_gitlab - created_at_gitlab)) / 3600
 *            )
 *            — по неделям для графика, суммарно для карточки
 *   Единица: часы
 *
 *   Что измеряет:
 *     Медиана времени от открытия MR до мержа в dev-ветку.
 *     Отражает только цикл код-ревью.
 *
 *   ⚠ Это НЕ полный DORA Lead Time for Changes.
 *     Настоящий Lead Time = первый коммит → деплой в прод.
 *     Метрика намеренно не включает:
 *       • ожидание релизного фриза и создания тега
 *       • регрессионное окно на стейдже (обычно 1–2 дня)
 *       • деплой в продакшн
 *     Реальный end-to-end Lead Time (mr.created_at → prod_deployed_at)
 *     отображается в секции «Релизы» на той же странице.
 *
 *   Пороги DORA (меньше = лучше):
 *     Elite  ≤ 4 ч    (меньше полурабочего дня)
 *     High   4–24 ч   (в рамках одного рабочего дня)
 *     Medium 24–72 ч  (1–3 рабочих дня)
 *     Low    &gt; 72 ч
 * </pre>
 *
 * <h3>2. DEPLOYMENT FREQUENCY — частота деплоев</h3>
 * <pre>
 *   Статус: COMING_SOON
 *   Источник: GitLab Deployments API (GET /projects/:id/deployments)
 *              или release_tag.prod_deployed_at (из ReleaseSyncService)
 *   Формула: COUNT(успешных деплоев в прод) / кол-во дней периода
 *   Единица: деплоев/день
 *
 *   Пороги DORA (больше = лучше):
 *     Elite  ≥ 1/день  (по требованию, непрерывный деплой)
 *     High   ≥ 1/нед   (≥ 0.143/день)
 *     Medium ≥ 1/мес   (≥ 0.033/день)
 *     Low    &lt; 1/мес
 * </pre>
 *
 * <h3>3. CHANGE FAILURE RATE — процент неудачных изменений</h3>
 * <pre>
 *   Статус: COMING_SOON
 *   Источник: Вариант А — GitLab pipeline failures на prod/release ветке
 *              Вариант Б — Jira-инциденты, связанные с релизом (label = "incident")
 *   Формула: COUNT(деплоев → инцидент) / COUNT(всех деплоев) × 100
 *   Единица: проценты (%)
 *
 *   Пороги DORA (меньше = лучше):
 *     Elite  &lt; 5 %
 *     High   5–10 %
 *     Medium 10–15 %
 *     Low    &gt; 15 %
 * </pre>
 *
 * <h3>4. MTTR — среднее время восстановления</h3>
 * <pre>
 *   Статус: COMING_SOON
 *   Источник: Jira Issues (issuetype = Incident, resolved = true)
 *              или GitLab Issues с меткой "incident"
 *   Формула: MEAN(resolved_at - created_at) по инцидентам за период
 *   Единица: часы
 *
 *   Пороги DORA (меньше = лучше):
 *     Elite  &lt; 1 ч
 *     High   &lt; 24 ч    (в тот же день)
 *     Medium &lt; 168 ч   (в течение недели)
 *     Low    ≥ 168 ч
 * </pre>
 */
public enum DoraMetric {

    // ── Available ─────────────────────────────────────────────────────────────

    PR_CYCLE_TIME(
        "pr_cycle_time",
        "PR Cycle Time",
        "Медиана от открытия MR до мержа в dev-ветку. "
            + "Прокси для Lead Time — не включает ожидание релиза и деплой в прод.",
        Unit.HOURS,
        Status.AVAILABLE,
        Direction.LOWER_IS_BETTER,
        4.0, 24.0, 72.0
    ),

    // ── Скоро ────────────────────────────────────────────────────────────────

    DEPLOYMENT_FREQUENCY(
        "deployment_frequency",
        "Deploy Frequency",
        "Частота деплоев в prod-окружение за период. Требует подключения GitLab Deployments API.",
        Unit.DEPLOYS_PER_DAY,
        Status.COMING_SOON,
        Direction.HIGHER_IS_BETTER,
        1.0, 1.0 / 7, 1.0 / 30
    ),

    CHANGE_FAILURE_RATE(
        "change_failure_rate",
        "Change Failure Rate",
        "Процент деплоев, вызвавших инциденты. Требует интеграции с Jira или GitLab Issues.",
        Unit.PERCENT,
        Status.COMING_SOON,
        Direction.LOWER_IS_BETTER,
        5.0, 10.0, 15.0
    ),

    MTTR(
        "mttr",
        "MTTR",
        "Среднее время восстановления после инцидента. Требует интеграции с Jira или GitLab Issues.",
        Unit.HOURS,
        Status.COMING_SOON,
        Direction.LOWER_IS_BETTER,
        1.0, 24.0, 168.0
    );

    // ── Enum infrastructure ───────────────────────────────────────────────────

    private final String key;
    private final String label;
    private final String description;
    private final Unit unit;
    private final Status status;
    private final Direction direction;

    /**
     * First boundary — Elite/High split.
     * Lower-is-better: Elite if value ≤ this. Higher-is-better: Elite if value ≥ this.
     */
    private final double eliteThreshold;

    /**
     * Second boundary — High/Medium split.
     */
    private final double highThreshold;

    /**
     * Third boundary — Medium/Low split.
     */
    private final double mediumThreshold;

    @SuppressWarnings("checkstyle:ParameterNumber")
    DoraMetric(String key,
               String label,
               String description,
               Unit unit,
               Status status,
               Direction direction,
               double eliteThreshold,
               double highThreshold,
               double mediumThreshold) {
        this.key = key;
        this.label = label;
        this.description = description;
        this.unit = unit;
        this.status = status;
        this.direction = direction;
        this.eliteThreshold = eliteThreshold;
        this.highThreshold = highThreshold;
        this.mediumThreshold = mediumThreshold;
    }

    /**
     * Вычисляет DORA-рейтинг для измеренного значения.
     * Возвращает {@link DoraRating#NO_DATA} если метрика ещё не реализована или значение null.
     */
    public DoraRating computeRating(Double value) {
        if (value == null || status == Status.COMING_SOON) {
            return DoraRating.NO_DATA;
        }
        return direction == Direction.LOWER_IS_BETTER
            ? ratingLowerIsBetter(value)
            : ratingHigherIsBetter(value);
    }

    private DoraRating ratingLowerIsBetter(double value) {
        if (value <= eliteThreshold) {
            return DoraRating.ELITE;
        }
        if (value <= highThreshold) {
            return DoraRating.HIGH;
        }
        if (value <= mediumThreshold) {
            return DoraRating.MEDIUM;
        }
        return DoraRating.LOW;
    }

    private DoraRating ratingHigherIsBetter(double value) {
        if (value >= eliteThreshold) {
            return DoraRating.ELITE;
        }
        if (value >= highThreshold) {
            return DoraRating.HIGH;
        }
        if (value >= mediumThreshold) {
            return DoraRating.MEDIUM;
        }
        return DoraRating.LOW;
    }

    /** Finds a metric by its key; returns {@code null} if not found. */
    public static DoraMetric forKey(String key) {
        for (DoraMetric m : values()) {
            if (m.key.equals(key)) {
                return m;
            }
        }
        return null;
    }

    /** Unique string identifier (e.g. {@code "pr_cycle_time"}). */
    public String key() {
        return key;
    }

    /** Short English label for the metric card (e.g. {@code "PR Cycle Time"}). */
    public String label() {
        return label;
    }

    /** One-sentence Russian description of the data source and calculation. */
    public String description() {
        return description;
    }

    /** Physical unit of the metric value. */
    public Unit unit() {
        return unit;
    }

    /** Whether the metric is currently implemented or only planned. */
    public Status status() {
        return status;
    }

    /** True if this metric has real data and can show a rating. */
    public boolean isAvailable() {
        return status == Status.AVAILABLE;
    }

    // ── Nested types ──────────────────────────────────────────────────────────

    /** Physical unit of a DORA metric value. */
    public enum Unit {
        HOURS,
        DEPLOYS_PER_DAY,
        PERCENT
    }

    /** Implementation status of a DORA metric. */
    public enum Status {
        AVAILABLE,
        COMING_SOON
    }

    /** Rating direction: whether a lower or higher value is better. */
    public enum Direction {
        LOWER_IS_BETTER,
        HIGHER_IS_BETTER
    }
}
