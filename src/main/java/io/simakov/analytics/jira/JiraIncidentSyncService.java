package io.simakov.analytics.jira;

import io.simakov.analytics.domain.model.JiraIncident;
import io.simakov.analytics.domain.model.TrackedProject;
import io.simakov.analytics.domain.repository.JiraIncidentRepository;
import io.simakov.analytics.domain.repository.TrackedProjectRepository;
import io.simakov.analytics.jira.dto.JiraIssueDto;
import io.simakov.analytics.security.WorkspaceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Syncs Jira incidents and matches them to tracked projects via component names.
 *
 * <p>Matching logic: for each incident component, a case-insensitive exact match
 * against {@link TrackedProject#getName()} is attempted. If a project name
 * matches a component, the incident is linked to that project.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JiraIncidentSyncService {

    private final JiraApiClient jiraApiClient;
    private final JiraIncidentRepository jiraIncidentRepository;
    private final TrackedProjectRepository trackedProjectRepository;

    private static String truncate(String value,
                                   int maxLen) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLen
            ? value
            : value.substring(0, maxLen);
    }

    /**
     * Fetches incidents from Jira created within the last {@code days} days
     * and upserts them, linking to tracked projects by component name.
     * Uses {@link WorkspaceContext} to determine workspace.
     *
     * @return number of incident-project links persisted
     */
    @Transactional
    public int syncIncidents(int days) {
        return syncIncidentsForWorkspace(WorkspaceContext.get(), days);
    }

    /**
     * Fetches incidents from Jira for a specific workspace.
     * Does not depend on {@link WorkspaceContext} — safe to call from scheduled jobs.
     *
     * @return number of incident-project links persisted
     */
    @Transactional
    public int syncIncidentsForWorkspace(Long workspaceId,
                                         int days) {
        Instant since = Instant.now().minus(days, ChronoUnit.DAYS);

        Map<String, TrackedProject> projectByNameLower = buildProjectIndex(workspaceId);
        if (projectByNameLower.isEmpty()) {
            log.info("No tracked projects in workspace {} — skipping Jira incident sync", workspaceId);
            return 0;
        }

        List<JiraIssueDto> incidents = jiraApiClient.fetchIncidents(since);
        int persisted = 0;

        for (JiraIssueDto issue : incidents) {
            persisted += persistIssueLinks(issue, projectByNameLower, workspaceId);
        }

        log.info("Jira incident sync complete: workspace={}, {} incidents fetched, {} project links persisted",
            workspaceId, incidents.size(), persisted);
        return persisted;
    }

    private Map<String, TrackedProject> buildProjectIndex(Long workspaceId) {
        List<TrackedProject> projects =
            trackedProjectRepository.findAllByWorkspaceIdAndEnabledTrue(workspaceId);
        Map<String, TrackedProject> index = new HashMap<>();
        for (TrackedProject p : projects) {
            index.put(p.getName().toLowerCase(Locale.ROOT), p);
        }
        return index;
    }

    private int persistIssueLinks(JiraIssueDto issue,
                                  Map<String, TrackedProject> projectIndex,
                                  Long workspaceId) {
        if (issue.fields() == null || issue.fields().components() == null) {
            return 0;
        }
        int count = 0;
        for (JiraIssueDto.Component component : issue.fields().components()) {
            if (component.name() == null) {
                continue;
            }
            TrackedProject matched = projectIndex.get(component.name().toLowerCase(Locale.ROOT));
            if (matched == null) {
                log.debug("Jira component '{}' from {} does not match any tracked project",
                    component.name(), issue.key());
                continue;
            }
            upsertIncident(issue, component, matched, workspaceId);
            count++;
        }
        return count;
    }

    private void upsertIncident(JiraIssueDto issue,
                                JiraIssueDto.Component component,
                                TrackedProject project,
                                Long workspaceId) {
        JiraIncident entity = jiraIncidentRepository
            .findByJiraKeyAndTrackedProjectId(issue.key(), project.getId())
            .orElseGet(() -> JiraIncident.builder()
                .workspaceId(workspaceId)
                .trackedProjectId(project.getId())
                .jiraKey(issue.key())
                .build());

        entity.setSummary(truncate(issue.fields().summary(), 1024));
        if (issue.fields().created() == null) {
            log.warn("Jira issue {} has no created date — skipping", issue.key());
            return;
        }
        entity.setCreatedAt(issue.fields().created().toInstant());
        entity.setResolvedAt(issue.fields().resolutiondate() != null
            ? issue.fields().resolutiondate().toInstant()
            : null);
        entity.setComponentName(truncate(component.name(), 255));
        entity.setImpactStartedAt(issue.fields().impactStartedAt() != null
            ? issue.fields().impactStartedAt().toInstant()
            : null);
        entity.setImpactEndedAt(issue.fields().impactEndedAt() != null
            ? issue.fields().impactEndedAt().toInstant()
            : null);
        jiraIncidentRepository.save(entity);
    }
}
