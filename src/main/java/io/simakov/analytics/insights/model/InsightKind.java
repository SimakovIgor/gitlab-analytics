package io.simakov.analytics.insights.model;

/**
 * Severity category of a team insight.
 * Controls icon, colour, and sort priority in the UI.
 */
public enum InsightKind {

    /** Critical problem requiring immediate attention. */
    BAD("bad"),

    /** Warning — worth investigating soon. */
    WARN("warn"),

    /** Positive signal worth highlighting. */
    GOOD("good"),

    /** Neutral observation / informational. */
    INFO("info");

    private final String cssValue;

    InsightKind(String cssValue) {
        this.cssValue = cssValue;
    }

    /**
     * Value used in CSS class names and Thymeleaf conditionals (e.g. {@code insight-row--bad}).
     */
    public String cssValue() {
        return cssValue;
    }
}
