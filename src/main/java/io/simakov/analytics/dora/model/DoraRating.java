package io.simakov.analytics.dora.model;

/**
 * Уровень производительности DORA (четыре диапазона по исследованию State of DevOps).
 *
 * <ul>
 *   <li>{@link #ELITE}   — топ-исполнители: короткий цикл, частые деплои</li>
 *   <li>{@link #HIGH}    — выше среднего, зрелые инженерные практики</li>
 *   <li>{@link #MEDIUM}  — типичный средний уровень, есть куда расти</li>
 *   <li>{@link #LOW}     — ниже среднего, заметные узкие места</li>
 *   <li>{@link #NO_DATA} — недостаточно данных для присвоения рейтинга</li>
 * </ul>
 *
 * <p>Каждая константа хранит метку для UI и CSS-класс для стиля {@code .rating-pill-*}
 * из {@code analytics.css}.
 */
public enum DoraRating {

    ELITE("Elite", "rating-pill-elite",
        "< 1 часа · топ-10% команд в мире. Изменения летят в прод мгновенно, CI/CD полностью автоматизирован."),
    HIGH("High", "rating-pill-high",
        "< 7 дней · зрелые инженерные практики. Хороший CI/CD, изменения достигают прода за часы-дни."),
    MEDIUM("Medium", "rating-pill-medium",
        "7–30 дней · типичная команда. Есть потенциал: автоматизация, уменьшение размера MR, ускорение ревью."),
    LOW("Low", "rating-pill-low",
        "30+ дней · медленный цикл доставки. Риски копятся, деплои болезненны — стоит разобраться с причинами."),
    NO_DATA("Нет данных", "rating-pill-tbd",
        "Недостаточно данных. Обновите релизы на странице DORA — нужны теги с датой деплоя в прод.");

    private final String label;
    private final String cssClass;
    private final String description;

    DoraRating(String label,
               String cssClass,
               String description) {
        this.label = label;
        this.cssClass = cssClass;
        this.description = description;
    }

    /**
     * Short label displayed inside the rating pill.
     */
    public String label() {
        return label;
    }

    /**
     * CSS modifier class for {@code .rating-pill} (e.g. {@code "rating-pill-elite"}).
     */
    public String cssClass() {
        return cssClass;
    }

    /**
     * Short description of the rating tier shown in a tooltip.
     */
    public String description() {
        return description;
    }
}
