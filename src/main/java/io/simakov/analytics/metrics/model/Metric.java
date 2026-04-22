package io.simakov.analytics.metrics.model;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Canonical registry of all metrics produced by MetricCalculationService.
 * <p>
 * Each constant carries:
 * <ul>
 *   <li>{@link #key()}         — JSON/DB key used in metrics_json and API responses</li>
 *   <li>{@link #label()}       — short Russian label for UI dropdowns and table headers</li>
 *   <li>{@link #description()} — one-sentence Russian explanation for the metrics legend</li>
 *   <li>{@link #category()}    — logical grouping</li>
 *   <li>{@link #isInMinutes()} — true when the stored value is in minutes; UI shows it in hours</li>
 *   <li>{@link #isChartVisible()} — true when the metric appears in the History chart dropdown</li>
 * </ul>
 */
public enum Metric {

    // ── Delivery ─────────────────────────────────────────────────────────────

    MR_OPENED_COUNT("mr_opened_count", "MR открыто",
        "Число MR, открытых сотрудником в периоде.",
        Category.DELIVERY, false, false),

    MR_MERGED_COUNT("mr_merged_count", "MR смерджено",
        "Число MR, слитых в периоде. Основной показатель доставки.",
        Category.DELIVERY, false, true),

    ACTIVE_DAYS_COUNT("active_days_count", "Активных дней",
        "Дней с хотя бы одним коммитом, комментарием или аппрувом. Показывает регулярность.",
        Category.DELIVERY, false, true),

    REPOSITORIES_TOUCHED_COUNT("repositories_touched_count", "Репозиториев",
        "Число репозиториев, в которых был хотя бы один коммит в периоде.",
        Category.DELIVERY, false, false),

    COMMITS_IN_MR_COUNT("commits_in_mr_count", "Коммиты",
        "Число коммитов в смерджённых MR, авторство которых принадлежит сотруднику.",
        Category.DELIVERY, false, true),

    // ── Change volume ─────────────────────────────────────────────────────────

    LINES_ADDED("lines_added", "+ строк",
        "Строк добавлено в смерджённых MR. Косвенно говорит об объёме задач.",
        Category.CHANGE_VOLUME, false, true),

    LINES_DELETED("lines_deleted", "− строк",
        "Строк удалено в смерджённых MR.",
        Category.CHANGE_VOLUME, false, true),

    LINES_CHANGED("lines_changed", "Строк изменено",
        "Сумма добавленных и удалённых строк в смерджённых MR.",
        Category.CHANGE_VOLUME, false, false),

    FILES_CHANGED("files_changed", "Файлов изменено",
        "Число уникальных файлов, затронутых в смерджённых MR.",
        Category.CHANGE_VOLUME, false, false),

    AVG_MR_SIZE_LINES("avg_mr_size_lines", "Средний MR (строк)",
        "Среднее число изменённых строк на один MR.",
        Category.CHANGE_VOLUME, false, false),

    MEDIAN_MR_SIZE_LINES("median_mr_size_lines", "Медиана MR (строк)",
        "Медиана числа изменённых строк на один MR.",
        Category.CHANGE_VOLUME, false, false),

    AVG_MR_SIZE_FILES("avg_mr_size_files", "Средний MR (файлов)",
        "Среднее число файлов на один MR.",
        Category.CHANGE_VOLUME, false, false),

    // ── Review ────────────────────────────────────────────────────────────────

    REVIEW_COMMENTS_WRITTEN_COUNT("review_comments_written_count", "Комментарии",
        "Комментарии, оставленные при ревью чужих MR. Показывает вовлечённость в код-ревью.",
        Category.REVIEW, false, true),

    MRS_REVIEWED_COUNT("mrs_reviewed_count", "Ревью MR",
        "Число чужих MR, в которых сотрудник оставил хотя бы один комментарий или аппрув.",
        Category.REVIEW, false, true),

    APPROVALS_GIVEN_COUNT("approvals_given_count", "Аппрувы",
        "Число выданных аппрувов. Не равно MR отревьюено — можно смотреть без аппрува.",
        Category.REVIEW, false, true),

    REVIEW_THREADS_STARTED_COUNT("review_threads_started_count", "Треды",
        "Число дискуссионных тредов, открытых сотрудником в чужих MR.",
        Category.REVIEW, false, true),

    // ── Flow ──────────────────────────────────────────────────────────────────

    AVG_TIME_TO_FIRST_REVIEW_MINUTES("avg_time_to_first_review_minutes", "До ревью",
        "Среднее время от открытия MR до первого комментария или аппрува (в часах).",
        Category.FLOW, true, true),

    MEDIAN_TIME_TO_FIRST_REVIEW_MINUTES("median_time_to_first_review_minutes", "Медиана до первого ревью (ч)",
        "Медиана времени от открытия MR до первого комментария или аппрува (в часах).",
        Category.FLOW, true, false),

    AVG_TIME_TO_MERGE_MINUTES("avg_time_to_merge_minutes", "До мержа",
        "Среднее время от открытия MR до мержа (в часах). Чем меньше — тем быстрее проходит ревью.",
        Category.FLOW, true, true),

    MEDIAN_TIME_TO_MERGE_MINUTES("median_time_to_merge_minutes", "Медиана Time to Merge",
        "Медиана времени от создания MR до мержа в dev (в часах).",
        Category.FLOW, true, true),

    REWORK_MR_COUNT("rework_mr_count", "MR с доработкой",
        "Число MR, в которые сотрудник вносил изменения после создания ревью-треда.",
        Category.FLOW, false, false),

    REWORK_RATIO("rework_ratio", "Доля доработок",
        "Отношение MR с доработкой к общему числу смерджённых MR.",
        Category.FLOW, false, false),

    // ── Normalized ────────────────────────────────────────────────────────────

    MR_MERGED_PER_ACTIVE_DAY("mr_merged_per_active_day", "MR/активный день",
        "Среднее число смерджённых MR за один активный день. Нормализует скорость к занятости.",
        Category.NORMALIZED, false, false),

    COMMENTS_PER_REVIEWED_MR("comments_per_reviewed_mr", "Комментариев на MR",
        "Среднее число комментариев на один отревьюенный MR. Показывает глубину ревью.",
        Category.NORMALIZED, false, false);

    // ── Enum infrastructure ───────────────────────────────────────────────────

    private final String key;
    private final String label;
    private final String description;
    private final Category category;
    private final boolean inMinutes;
    private final boolean chartVisible;

    Metric(String key,
           String label,
           String description,
           Category category,
           boolean inMinutes,
           boolean chartVisible) {
        this.key = key;
        this.label = label;
        this.description = description;
        this.category = category;
        this.inMinutes = inMinutes;
        this.chartVisible = chartVisible;
    }

    /**
     * Returns metric keys of all metrics whose values are stored in minutes.
     * Convenience helper for time-unit conversion in controllers.
     */
    public static Set<String> minuteKeys() {
        return Arrays.stream(values())
            .filter(Metric::isInMinutes)
            .map(Metric::key)
            .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Returns an ordered label map of chart-visible metrics: {@code key → label}.
     * Preserves declaration order (insertion order of the enum constants).
     */
    public static Map<String, String> chartOptions() {
        return Arrays.stream(values())
            .filter(Metric::isChartVisible)
            .collect(Collectors.toMap(Metric::key, Metric::label,
                (a, b) -> a, LinkedHashMap::new));
    }

    /**
     * Finds a metric by its JSON key; returns {@code null} if not found.
     */
    public static Metric forKey(String key) {
        for (Metric m : values()) {
            if (m.key.equals(key)) {
                return m;
            }
        }
        return null;
    }

    /**
     * JSON/DB key for this metric (e.g. {@code "mr_merged_count"}).
     */
    public String key() {
        return key;
    }

    /**
     * Short Russian UI label (e.g. {@code "MR смерджено"}).
     */
    public String label() {
        return label;
    }

    /**
     * One-sentence Russian description for the metrics legend.
     */
    public String description() {
        return description;
    }

    /**
     * Logical category.
     */
    public Category category() {
        return category;
    }

    /**
     * True when the stored value is in minutes and should be displayed as hours.
     */
    public boolean isInMinutes() {
        return inMinutes;
    }

    /**
     * True when this metric appears in the History chart dropdown.
     */
    public boolean isChartVisible() {
        return chartVisible;
    }

    /**
     * Logical grouping of metrics.
     */
    public enum Category {
        DELIVERY, CHANGE_VOLUME, REVIEW, FLOW, NORMALIZED
    }
}
