package io.simakov.analytics.domain.repository;

/**
 * Проекция еженедельных строк реального Lead Time for Changes.
 * Значения в днях (дробных): {@code prod_deployed_at - mr.created_at_gitlab}.
 * Столбцы: period, mr_count, median_days, p75_days, p95_days.
 */
public interface RealLeadTimeWeekProjection {

    Object getPeriod();

    Long getMrCount();

    Double getMedianDays();

    Double getP75Days();

    Double getP95Days();
}
