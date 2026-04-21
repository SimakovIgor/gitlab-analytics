package io.simakov.analytics.metrics.provider;

import io.simakov.analytics.metrics.MetricContext;
import io.simakov.analytics.metrics.model.UserMetrics;

/**
 * Strategy interface for computing a group of metrics.
 * Each implementation receives a fully populated {@link MetricContext} and
 * fills in its section of {@link UserMetrics.UserMetricsBuilder}.
 *
 * <p>Spring collects all beans of this type into a {@code List<MetricProvider>}
 * (in {@link org.springframework.core.annotation.Order} order) which
 * {@code MetricCalculationService} iterates for each user.
 */
public interface MetricProvider {

    void populate(MetricContext ctx,
                  UserMetrics.UserMetricsBuilder builder);
}
