package io.simakov.analytics.insights.model;

/**
 * Canonical registry of all algorithmic insight rules.
 * <p>
 * Each constant carries:
 * <ul>
 *   <li>{@link #code()}           — unique string identifier shown in the UI (monospace badge)</li>
 *   <li>{@link #defaultKind()}    — default severity category (evaluator may override per-trigger)</li>
 *   <li>{@link #defaultSeverity()} — default 1-5 score (evaluator may override based on magnitude)</li>
 *   <li>{@link #description()}    — one-sentence Russian explanation of what the rule checks</li>
 * </ul>
 */
public enum InsightRule {

    HIGH_MERGE_TIME(
        "HIGH_MERGE_TIME",
        InsightKind.BAD,
        4,
        "Командная медиана Time to Merge (от создания MR до мержа в dev) превышает целевое значение."
    ),

    MERGE_TIME_SPIKE(
        "MERGE_TIME_SPIKE",
        InsightKind.BAD,
        4,
        "Медиана Time to Merge значительно выросла по сравнению с предыдущим периодом."
    ),

    STUCK_MRS(
        "STUCK_MRS",
        InsightKind.BAD,
        5,
        "MR, которые висят открытыми дольше порогового времени без движения."
    ),

    REVIEW_LOAD_IMBALANCE(
        "REVIEW_LOAD_IMBALANCE",
        InsightKind.WARN,
        3,
        "Нагрузка по ревью сильно перекошена: коэффициент Джини превышает порог."
    ),

    LARGE_MR_HABIT(
        "LARGE_MR_HABIT",
        InsightKind.WARN,
        2,
        "Один или несколько разработчиков регулярно создают большие MR."
    ),

    DELIVERY_DROP(
        "DELIVERY_DROP",
        InsightKind.WARN,
        3,
        "Объём влитых MR значительно упал по сравнению с прошлым периодом."
    ),

    LOW_REVIEW_DEPTH(
        "LOW_REVIEW_DEPTH",
        InsightKind.INFO,
        2,
        "Среднее число комментариев на отревьюенный MR ниже целевого — ревью может быть поверхностным."
    ),

    HIGH_REWORK_RATIO(
        "HIGH_REWORK_RATIO",
        InsightKind.WARN,
        3,
        "Высокая доля MR, в которых автор вносил правки после открытия ревью-треда."
    ),

    LOW_ACTIVITY(
        "LOW_ACTIVITY",
        InsightKind.WARN,
        2,
        "Участник с активностью значительно ниже медианы команды (влитых MR < 30% от медианы)."
    ),

    NO_CODE_REVIEW(
        "NO_CODE_REVIEW",
        InsightKind.BAD,
        3,
        "Активный разработчик мержит MR, но не ревьюит чужие — нарушает баланс команды."
    ),

    SLOW_LEAD_TIME(
        "SLOW_LEAD_TIME",
        InsightKind.BAD,
        4,
        "DORA Lead Time for Changes (от создания MR до деплоя в прод) превышает целевой порог."
    ),

    LOW_DEPLOY_FREQUENCY(
        "LOW_DEPLOY_FREQUENCY",
        InsightKind.WARN,
        3,
        "DORA Deploy Frequency ниже целевого порога — деплои в прод происходят слишком редко."
    ),

    LEAD_TIME_REGRESSION(
        "LEAD_TIME_REGRESSION",
        InsightKind.BAD,
        4,
        "DORA Lead Time for Changes значительно вырос по сравнению с предыдущим периодом."
    );

    private final String code;
    private final InsightKind defaultKind;
    private final int defaultSeverity;
    private final String description;

    InsightRule(String code,
                InsightKind defaultKind,
                int defaultSeverity,
                String description) {
        this.code = code;
        this.defaultKind = defaultKind;
        this.defaultSeverity = defaultSeverity;
        this.description = description;
    }

    /**
     * Unique rule code shown in the UI as a monospace badge.
     */
    public String code() {
        return code;
    }

    /**
     * Default severity category; evaluator may produce a different kind if triggered conditionally.
     */
    public InsightKind defaultKind() {
        return defaultKind;
    }

    /**
     * Default severity score 1–5; evaluator may adjust based on magnitude of the violation.
     */
    public int defaultSeverity() {
        return defaultSeverity;
    }

    /**
     * One-sentence Russian explanation of what this rule checks.
     */
    public String description() {
        return description;
    }
}
