package io.simakov.analytics.digest;

import io.simakov.analytics.domain.model.Workspace;
import io.simakov.analytics.domain.repository.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Sends weekly team digest every Monday at 09:00 UTC.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WeeklyDigestScheduler {

    private final WorkspaceRepository workspaceRepository;
    private final DigestService digestService;
    private final DigestEmailSender digestEmailSender;

    @Scheduled(cron = "0 0 9 * * MON", zone = "UTC")
    public void sendWeeklyDigests() {
        List<Workspace> workspaces = workspaceRepository.findAllByDigestEnabled(true);
        log.info("Weekly digest: sending to {} workspace(s)", workspaces.size());

        for (Workspace workspace : workspaces) {
            try {
                digestService.build(workspace).ifPresent(digestEmailSender::send);
            } catch (Exception e) {
                log.error("Digest failed for workspace={}: {}", workspace.getId(), e.getMessage(), e);
            }
        }

        log.info("Weekly digest: done");
    }
}
