package io.simakov.analytics.dora.model;

/**
 * Describes the data quality state of a DORA metric source.
 * Used in Settings → DORA diagnostics panel and on the DORA dashboard cards.
 */
public enum DataQualityState {
    /** Source configured, events present, metric is computable. */
    CONFIGURED_OK,
    /** Source not configured. */
    NOT_CONFIGURED,
    /** Source configured but no events found in the selected period. */
    NO_EVENTS,
    /** Some events lack service mapping — metric may be incomplete. */
    PARTIAL_DATA,
    /** Service mapping is missing — metric cannot be computed. */
    MAPPING_REQUIRED,
    /** Fewer than 5 events — result is statistically unreliable. */
    INSUFFICIENT_DATA
}
