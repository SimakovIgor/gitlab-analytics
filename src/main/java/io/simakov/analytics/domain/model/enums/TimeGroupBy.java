package io.simakov.analytics.domain.model.enums;

/**
 * Time granularity for grouping metric snapshot history.
 * Distinct from {@link GroupBy} which is a dimensional grouping (user/project).
 */
public enum TimeGroupBy {
    DAY,
    WEEK,
    MONTH
}
