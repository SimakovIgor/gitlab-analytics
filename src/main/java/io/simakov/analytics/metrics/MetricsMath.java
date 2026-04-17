package io.simakov.analytics.metrics;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Вспомогательные математические операции для расчёта метрик.
 * Все методы — чистые функции без состояния.
 */
final class MetricsMath {

    private MetricsMath() {
    }

    /**
     * Среднее арифметическое списка чисел.
     * Возвращает 0 для пустого списка.
     */
    static double mean(List<? extends Number> values) {
        if (values.isEmpty()) {
            return 0;
        }
        return values.stream().mapToDouble(Number::doubleValue).average().orElse(0);
    }

    /**
     * Медиана предварительно отсортированного списка.
     * Возвращает 0 для пустого списка.
     */
    static double median(List<? extends Number> sorted) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int size = sorted.size();
        if (size % 2 == 1) {
            return sorted.get(size / 2).doubleValue();
        }
        return (sorted.get(size / 2 - 1).doubleValue() + sorted.get(size / 2).doubleValue()) / 2.0;
    }

    /** Округление до 2 знаков после запятой. */
    static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /**
     * Среднее с округлением до 2 знаков, или {@code null} для пустого списка.
     * Используется для nullable-полей времени в {@link io.simakov.analytics.metrics.model.UserMetrics}.
     */
    static Double optMean(List<Long> values) {
        return values.isEmpty() ? null : round2(mean(values));
    }

    /**
     * Медиана с округлением до 2 знаков, или {@code null} для пустого списка.
     * Используется для nullable-полей времени в {@link io.simakov.analytics.metrics.model.UserMetrics}.
     */
    static Double optMedian(List<Long> values) {
        return values.isEmpty() ? null : round2(median(values));
    }

    /** Преобразует метку времени в строку даты в UTC (формат yyyy-MM-dd). */
    static String toDateString(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC).toLocalDate().toString();
    }
}
