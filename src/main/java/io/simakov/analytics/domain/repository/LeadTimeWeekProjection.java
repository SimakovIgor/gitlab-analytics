package io.simakov.analytics.domain.repository;

/**
 * Projection for weekly lead-time rows returned by
 * {@link MergeRequestRepository#findLeadTimeByWeek}.
 * Columns: period, mr_count, median_hours, p75_hours, p95_hours.
 */
public interface LeadTimeWeekProjection {

    Object getPeriod();

    Long getMrCount();

    Double getMedianHours();

    Double getP75Hours();

    Double getP95Hours();
}
