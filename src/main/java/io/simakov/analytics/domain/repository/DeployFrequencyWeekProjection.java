package io.simakov.analytics.domain.repository;

/**
 * Weekly deployment count projection for Deploy Frequency chart.
 * Each row = one ISO week with the number of prod deployments.
 */
public interface DeployFrequencyWeekProjection {

    /**
     * ISO week label, e.g. "2026-W16".
     */
    String getWeekLabel();

    /**
     * Number of prod deployments in this week.
     */
    Long getDeployCount();
}
