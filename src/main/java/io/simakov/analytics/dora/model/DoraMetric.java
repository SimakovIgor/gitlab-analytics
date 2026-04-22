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
 * <h3>1. LEAD TIME FOR CHANGES — реальный Lead Time</h3>
 * <pre>
 *   Статус: AVAILABLE
 *   Источник: JOIN merge_request + release_tag (prod_deployed_at)
 *   Формула: PERCENTILE_CONT(0.5) WITHIN GROUP (
 *              ORDER BY EXTRACT(EPOCH FROM (rt.prod_deployed_at - mr.created_at_gitlab)) / 86400
 *            )
 *            — по неделям (группировка по дате prod_deployed_at) для графика,
 *              суммарно для карточки
 *   Единица: дни (дробные)
 *
 *   Что измеряет:
 *     Медиана времени от создания MR до деплоя релиза в продакшн.
 *     Учитывает: время ревью + ожидание фриза + регрессию на стейдже + деплой в прод.
 *     Это максимально близкое к DORA Lead Time for Changes значение
 *     без доступа к данным первого коммита фичи.
 *
 *   Ограничения:
 *     • Считается только по MR, у которых release_tag_id IS NOT NULL
 *       и release_tag.prod_deployed_at IS NOT NULL.
 *     • Новые MR (до первого синка релизов) и MR без prod deploy не попадают в выборку.
 *     • prod_deployed_at определяется по времени завершения первого успешного
 *       prod::deploy::* джоба в пайплайне тега.
 *
 *   Пороги DORA (официальные, State of DevOps 2023, меньше = лучше):
 *     Elite  &lt; 1 ч    (&lt; 1/24 дня ≈ 0.042 д)
 *     High   &lt; 7 д    (&lt; одной недели)
 *     Medium &lt; 30 д   (&lt; одного месяца)
 *     Low    ≥ 30 д
 * </pre>
 *
 * <h3>2. DEPLOYMENT FREQUENCY — частота деплоев</h3>
 * <pre>
 *   Статус: AVAILABLE
 *   Источник: release_tag.prod_deployed_at (из ReleaseSyncService)
 *   Формула: COUNT(release_tag WHERE prod_deployed_at IS NOT NULL
 *                  AND prod_deployed_at &gt;= dateFrom) / days_in_period
 *   Единица: деплоев/день
 *
 *   Что измеряет:
 *     Частота успешных деплоев в прод. Деплой = release tag, для которого
 *     найден успешный prod::deploy::* джоб в пайплайне тега.
 *     Теги без prod deploy (не нажали кнопку) не считаются.
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

    LEAD_TIME_FOR_CHANGES(
        "lead_time_for_changes",
        "Lead Time for Changes",
        "Медиана от создания MR до деплоя в прод. "
            + "Считается только по MR, попавшим в релиз с известной датой prod_deployed_at.",
        Unit.DAYS,
        Status.AVAILABLE,
        Direction.LOWER_IS_BETTER,
        1.0 / 24, 7.0, 30.0
    ),

    // ── Скоро ────────────────────────────────────────────────────────────────

    DEPLOYMENT_FREQUENCY(
        "deployment_frequency",
        "Deploy Frequency",
        "Частота деплоев в prod за период. "
            + "Считается по release_tag с известной датой prod_deployed_at.",
        Unit.DEPLOYS_PER_DAY,
        Status.AVAILABLE,
        Direction.HIGHER_IS_BETTER,
        1.0, 1.0 / 7, 1.0 / 30
    ),

    CHANGE_FAILURE_RATE(
        "change_failure_rate",
        "Change Failure Rate",
        "Процент деплоев, вызвавших инциденты. Считается по Jira-инцидентам проекта МИ, "
            + "привязанным к сервисам через поле Components.",
        Unit.PERCENT,
        Status.AVAILABLE,
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
     * Finds a metric by its key; returns {@code null} if not found.
     */
    public static DoraMetric forKey(String key) {
        for (DoraMetric m : values()) {
            if (m.key.equals(key)) {
                return m;
            }
        }
        return null;
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

    /**
     * Unique string identifier (e.g. {@code "pr_cycle_time"}).
     */
    public String key() {
        return key;
    }

    /**
     * Short English label for the metric card (e.g. {@code "PR Cycle Time"}).
     */
    public String label() {
        return label;
    }

    /**
     * One-sentence Russian description of the data source and calculation.
     */
    public String description() {
        return description;
    }

    /**
     * Physical unit of the metric value.
     */
    public Unit unit() {
        return unit;
    }

    /**
     * Whether the metric is currently implemented or only planned.
     */
    public Status status() {
        return status;
    }

    /**
     * True if this metric has real data and can show a rating.
     */
    public boolean isAvailable() {
        return status == Status.AVAILABLE;
    }

    /**
     * Returns a tooltip description specific to both this metric and the given rating tier.
     */
    @SuppressWarnings("checkstyle:CyclomaticComplexity")
    public String ratingDescription(DoraRating rating) {
        if (rating == DoraRating.NO_DATA) {
            return "Недостаточно данных для расчёта рейтинга.";
        }
        return switch (this) {
            case LEAD_TIME_FOR_CHANGES -> leadTimeDescription(rating);
            case DEPLOYMENT_FREQUENCY -> deployFreqDescription(rating);
            case CHANGE_FAILURE_RATE -> cfrDescription(rating);
            case MTTR -> mttrDescription(rating);
        };
    }

    private static String leadTimeDescription(DoraRating rating) {
        return switch (rating) {
            case ELITE -> "< 1 часа · топ-команды. Изменения попадают в прод практически мгновенно.";
            case HIGH -> "< 7 дней · зрелые практики. CI/CD отлажен, изменения доходят до прода за часы-дни.";
            case MEDIUM -> "7–30 дней · типичный уровень. Есть потенциал: автоматизация, уменьшение MR, ускорение ревью.";
            case LOW -> "30+ дней · медленный цикл. Риски копятся, деплои болезненны — стоит разобраться с причинами.";
            default -> "";
        };
    }

    private static String deployFreqDescription(DoraRating rating) {
        return switch (rating) {
            case ELITE -> "≥ 1/день · непрерывная доставка. Команда деплоит в прод несколько раз в день.";
            case HIGH -> "≥ 1/неделю · регулярные деплои. Релизный процесс отлажен.";
            case MEDIUM -> "≥ 1/месяц · редкие деплои. Стоит автоматизировать CI/CD и уменьшить размер релизов.";
            case LOW -> "< 1/месяц · деплои реже раза в месяц. Большие батчи, высокий риск при каждом релизе.";
            default -> "";
        };
    }

    private static String cfrDescription(DoraRating rating) {
        return switch (rating) {
            case ELITE -> "< 5% · практически все деплои проходят без инцидентов.";
            case HIGH -> "5–10% · редкие сбои. Хорошее покрытие тестами и процесс валидации.";
            case MEDIUM -> "10–15% · заметная доля проблемных деплоев. Стоит усилить тестирование.";
            case LOW -> "> 15% · высокая доля сбойных деплоев. Необходим пересмотр процесса релиза.";
            default -> "";
        };
    }

    private static String mttrDescription(DoraRating rating) {
        return switch (rating) {
            case ELITE -> "< 1 часа · мгновенное восстановление. Отличная готовность к инцидентам.";
            case HIGH -> "< 24 часов · восстановление в тот же день. Зрелый процесс реагирования.";
            case MEDIUM -> "< 7 дней · восстановление в течение недели. Есть потенциал ускорить реакцию.";
            case LOW -> "7+ дней · медленное восстановление. Необходимо выстроить процесс инцидент-менеджмента.";
            default -> "";
        };
    }

    // ── Nested types ──────────────────────────────────────────────────────────

    /**
     * Физическая единица значения DORA-метрики.
     */
    public enum Unit {
        HOURS,
        DAYS,
        DEPLOYS_PER_DAY,
        PERCENT
    }

    /**
     * Implementation status of a DORA metric.
     */
    public enum Status {
        AVAILABLE,
        COMING_SOON
    }

    /**
     * Rating direction: whether a lower or higher value is better.
     */
    public enum Direction {
        LOWER_IS_BETTER,
        HIGHER_IS_BETTER
    }
}
