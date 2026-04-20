package io.simakov.analytics.domain.repository;

/**
 * Projection for the lead-time summary row returned by
 * {@link MergeRequestRepository#findLeadTimeSummary}.
 * Columns: mr_count, median_hours, p75_hours, p95_hours.
 */
public interface LeadTimeSummaryProjection {

    Long getMrCount();

    Double getMedianHours();

    Double getP75Hours();

    Double getP95Hours();
}
