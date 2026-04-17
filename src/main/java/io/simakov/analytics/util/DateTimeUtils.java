package io.simakov.analytics.util;

import lombok.experimental.UtilityClass;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Утилитный класс для работы с датой и временем.
 * <p>
 * Все методы используют внутренние {@link Clock}, который по умолчанию совпадает с UTC.
 * Для управления временем в тестах используйте {@link #setClock(Clock)}:
 * <pre>
 *   Clock fixed = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
 *   Clock old = DateTimeUtils.setClock(fixed);
 *   try { ... }
 *   finally { DateTimeUtils.setClock(old); }
 * </pre>
 */
@UtilityClass
public class DateTimeUtils {

    private static final AtomicReference<Clock> CLOCK_REF =
        new AtomicReference<>(Clock.systemUTC());

    /**
     * Возвращает текущий Clock.
     */
    public static Clock clock() {
        return CLOCK_REF.get();
    }

    /**
     * Атомарно заменяет Clock и возвращает предыдущий.
     * Используется в тестах для фиксации «текущего времени».
     */
    public static Clock setClock(Clock newClock) {
        Objects.requireNonNull(newClock, "newClock cannot be null");
        return CLOCK_REF.getAndSet(newClock);
    }

    /**
     * Текущая дата в UTC.
     */
    public static LocalDate currentDateUtc() {
        return LocalDate.now(clock());
    }

    /**
     * Текущий момент времени.
     */
    public static Instant now() {
        return Instant.now(clock());
    }

    /**
     * Преобразует метку времени в строку даты в UTC (формат yyyy-MM-dd).
     */
    public static String toDateString(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC).toLocalDate().toString();
    }

    /**
     * Количество полных минут между двумя метками времени.
     */
    public static long minutesBetween(Instant from,
                                      Instant to) {
        return ChronoUnit.MINUTES.between(from, to);
    }

    /**
     * Начало дня (00:00:00 UTC) для заданной даты как метка времени.
     */
    public static Instant startOfDayUtc(LocalDate date) {
        return date.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /**
     * Вычитает заданное количество дней из метки времени.
     */
    public static Instant minusDays(Instant instant,
                                    long days) {
        return instant.minus(days, ChronoUnit.DAYS);
    }
}
