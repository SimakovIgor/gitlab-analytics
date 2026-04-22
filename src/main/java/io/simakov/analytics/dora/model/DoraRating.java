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

    ELITE("Elite", "rating-pill-elite"),
    HIGH("High", "rating-pill-high"),
    MEDIUM("Medium", "rating-pill-medium"),
    LOW("Low", "rating-pill-low"),
    NO_DATA("Нет данных", "rating-pill-tbd");

    private final String label;
    private final String cssClass;

    DoraRating(String label,
               String cssClass) {
        this.label = label;
        this.cssClass = cssClass;
    }

    /** Short label displayed inside the rating pill. */
    public String label() {
        return label;
    }

    /** CSS modifier class for {@code .rating-pill} (e.g. {@code "rating-pill-elite"}). */
    public String cssClass() {
        return cssClass;
    }
}
