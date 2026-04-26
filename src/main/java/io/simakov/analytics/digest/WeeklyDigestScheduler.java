package io.simakov.analytics.digest;

import io.simakov.analytics.domain.model.Workspace;
import io.simakov.analytics.domain.repository.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Sends weekly team digests on a per-workspace schedule.
 * Runs every hour (Europe/Moscow) and sends to workspaces whose configured
 * day + hour matches the current moment.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WeeklyDigestScheduler {

    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");

    private final WorkspaceRepository workspaceRepository;
    private final DigestService digestService;
    private final DigestEmailSender digestEmailSender;

    @Scheduled(cron = "0 0 * * * *", zone = "Europe/Moscow")
    public void sendDigests() {
        ZonedDateTime now = ZonedDateTime.now(MOSCOW);
        String day = now.getDayOfWeek().name().substring(0, 3);  // "MON", "TUE", …
        int hour = now.getHour();

        List<Workspace> due = workspaceRepository
            .findAllByDigestEnabledAndDigestDayAndDigestHour(true, day, hour);

        if (due.isEmpty()) {
            return;
        }

        log.info("Weekly digest: sending to {} workspace(s) [day={} hour={}]", due.size(), day, hour);
        for (Workspace workspace : due) {
            try {
                digestService.build(workspace).ifPresent(digestEmailSender::send);
            } catch (Exception e) {
                log.error("Digest failed for workspace={}: {}", workspace.getId(), e.getMessage(), e);
            }
        }
        log.info("Weekly digest: done");
    }
}
