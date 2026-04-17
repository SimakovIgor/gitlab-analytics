package io.simakov.analytics.sync;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Отвечает за два сценария зависших джобов:
 *
 * 1. Рестарт приложения — любой STARTED-джоб гарантированно осиротел,
 *    так как его поток умер вместе с предыдущим процессом.
 *    Обрабатывается при старте через ApplicationReadyEvent.
 *
 * 2. Зависший джоб без рестарта — джоб идёт дольше MAX_JOB_HOURS часов.
 *    Обрабатывается watchdog-шедулером каждые 15 минут.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StaleJobWatchdog {

    private static final int MAX_JOB_HOURS = 1;

    private final SyncJobService syncJobService;

    /**
     * При запуске приложения все STARTED-джобы, начатые до текущего момента,
     * являются осиротевшими (их потоки убиты вместе с предыдущим процессом).
     */
    @EventListener(ApplicationReadyEvent.class)
    public void failOrphanedJobsOnStartup() {
        Instant now = Instant.now();
        int count = syncJobService.failStaleJobs(
            now,
            "Прервано при перезапуске приложения"
        );
        if (count > 0) {
            log.warn("Startup cleanup: {} orphaned sync job(s) marked as FAILED", count);
        }
    }

    /**
     * Каждые 15 минут проверяет джобы, которые зависли дольше MAX_JOB_HOURS часов
     * (например, из-за дедлока или недоступности GitLab API).
     */
    @Scheduled(fixedDelay = 15 * 60 * 1000)
    public void failHungJobs() {
        Instant threshold = Instant.now().minus(MAX_JOB_HOURS, ChronoUnit.HOURS);
        int count = syncJobService.failStaleJobs(
            threshold,
            "Превышено максимальное время выполнения (" + MAX_JOB_HOURS + " ч)"
        );
        if (count > 0) {
            log.warn("Watchdog: {} hung sync job(s) marked as FAILED", count);
        }
    }
}
