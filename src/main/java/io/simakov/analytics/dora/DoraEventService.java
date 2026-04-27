package io.simakov.analytics.dora;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.simakov.analytics.api.dto.dora.DeployEventRequest;
import io.simakov.analytics.api.dto.dora.DoraEventResponse;
import io.simakov.analytics.api.dto.dora.IncidentEventRequest;
import io.simakov.analytics.domain.model.DoraDeployEvent;
import io.simakov.analytics.domain.model.DoraIncidentEvent;
import io.simakov.analytics.domain.model.DoraService;
import io.simakov.analytics.domain.model.DoraServiceMapping;
import io.simakov.analytics.domain.repository.DoraDeployEventRepository;
import io.simakov.analytics.domain.repository.DoraIncidentEventRepository;
import io.simakov.analytics.domain.repository.DoraServiceMappingRepository;
import io.simakov.analytics.domain.repository.DoraServiceRepository;
import io.simakov.analytics.dora.model.DeploySource;
import io.simakov.analytics.dora.model.DeployStatus;
import io.simakov.analytics.dora.model.IncidentSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class DoraEventService {

    private static final String SOURCE_TYPE_GITLAB = "GITLAB";
    private static final String SOURCE_TYPE_JIRA = "JIRA";

    private final DoraDeployEventRepository deployEventRepository;
    private final DoraIncidentEventRepository incidentEventRepository;
    private final DoraServiceRepository doraServiceRepository;
    private final DoraServiceMappingRepository doraServiceMappingRepository;
    private final ObjectMapper objectMapper;

    /**
     * Records a deployment event from the Manual API.
     * Idempotent: duplicate idempotency keys return the existing event without creating a new one.
     * Service resolution: if the service name is not found, the event is stored as unmatched
     * (doraServiceId = null) and a warning is included in the response.
     */
    @Transactional
    public DoraEventResponse recordDeploy(Long workspaceId, DeployEventRequest request) {
        Optional<DoraDeployEvent> existing =
            deployEventRepository.findByWorkspaceIdAndIdempotencyKey(
                workspaceId, request.idempotencyKey());
        if (existing.isPresent()) {
            DoraDeployEvent ev = existing.get();
            return DoraEventResponse.duplicate(ev.getId(), ev.getDoraServiceId() != null);
        }

        Long serviceId = resolveOrCreateService(workspaceId, request.service());
        String warning = serviceId == null
            ? "Service '" + request.service() + "' could not be resolved"
            : null;

        DoraDeployEvent event = DoraDeployEvent.builder()
            .workspaceId(workspaceId)
            .doraServiceId(serviceId)
            .environment(request.environment())
            .deployedAt(request.deployedAt())
            .status(request.status())
            .source(DeploySource.MANUAL_API)
            .version(request.version())
            .commitSha(request.commitSha())
            .commitRangeFrom(request.commitRangeFrom())
            .commitRangeTo(request.commitRangeTo())
            .idempotencyKey(request.idempotencyKey())
            .externalUrl(request.externalUrl())
            .metadata(serializeMetadata(request.metadata()))
            .build();

        DoraDeployEvent saved = deployEventRepository.save(event);
        log.info("Recorded deploy event id={} service='{}' env={} status={} workspace={}",
            saved.getId(), request.service(), request.environment(), request.status(), workspaceId);

        return warning != null
            ? DoraEventResponse.created(saved.getId(), false, warning)
            : DoraEventResponse.created(saved.getId(), true);
    }

    /**
     * Records an incident event from the Manual API.
     * Idempotent: duplicate idempotency keys return the existing event without creating a new one.
     */
    @Transactional
    public DoraEventResponse recordIncident(Long workspaceId, IncidentEventRequest request) {
        Optional<DoraIncidentEvent> existing =
            incidentEventRepository.findByWorkspaceIdAndIdempotencyKey(
                workspaceId, request.idempotencyKey());
        if (existing.isPresent()) {
            DoraIncidentEvent ev = existing.get();
            return DoraEventResponse.duplicate(ev.getId(), ev.getDoraServiceId() != null);
        }

        Long serviceId = resolveOrCreateService(workspaceId, request.service());
        String warning = serviceId == null
            ? "Service '" + request.service() + "' could not be resolved"
            : null;

        DoraIncidentEvent event = DoraIncidentEvent.builder()
            .workspaceId(workspaceId)
            .doraServiceId(serviceId)
            .startedAt(request.startedAt())
            .resolvedAt(request.resolvedAt())
            .severity(request.severity())
            .status(request.status())
            .source(IncidentSource.MANUAL_API)
            .externalId(request.externalId())
            .externalUrl(request.externalUrl())
            .idempotencyKey(request.idempotencyKey())
            .metadata(serializeMetadata(request.metadata()))
            .build();

        DoraIncidentEvent saved = incidentEventRepository.save(event);
        log.info("Recorded incident event id={} service='{}' started={} resolved={} workspace={}",
            saved.getId(), request.service(), request.startedAt(), request.resolvedAt(), workspaceId);

        return warning != null
            ? DoraEventResponse.created(saved.getId(), false, warning)
            : DoraEventResponse.created(saved.getId(), true);
    }

    /**
     * GitLab adapter: upserts a deployment event from a GitLab release tag.
     * Creates the DoraService and its GITLAB source mapping on first call.
     * Idempotent — safe to call repeatedly during sync.
     *
     * @param trackedProjectId used as the GITLAB source mapping key
     * @param projectName      used as the DoraService name
     * @param tagName          used as version and idempotency key suffix
     * @param deployedAt       prod_deployed_at from the release tag
     */
    @Transactional
    public void upsertDeployFromGitLab(Long workspaceId,
                                        Long trackedProjectId,
                                        String projectName,
                                        String tagName,
                                        Instant deployedAt) {
        String idempotencyKey = "gitlab-tag-" + trackedProjectId + "-" + tagName;
        Long serviceId = resolveOrCreateServiceWithMapping(workspaceId, projectName,
            SOURCE_TYPE_GITLAB, String.valueOf(trackedProjectId));

        deployEventRepository.findByWorkspaceIdAndIdempotencyKey(workspaceId, idempotencyKey)
            .ifPresentOrElse(
                existing -> {
                    existing.setDeployedAt(deployedAt);
                    existing.setVersion(tagName);
                    deployEventRepository.save(existing);
                },
                () -> deployEventRepository.save(DoraDeployEvent.builder()
                    .workspaceId(workspaceId)
                    .doraServiceId(serviceId)
                    .environment("production")
                    .deployedAt(deployedAt)
                    .status(DeployStatus.SUCCESS)
                    .source(DeploySource.GITLAB_TAGS)
                    .version(tagName)
                    .idempotencyKey(idempotencyKey)
                    .externalId(tagName)
                    .build())
            );
    }

    /**
     * Jira adapter: upserts an incident event from a Jira issue.
     * Creates the DoraService and its JIRA source mapping on first call.
     * Idempotent — safe to call repeatedly during sync.
     *
     * @param trackedProjectId used as the JIRA source mapping key
     * @param projectName      used as the DoraService name
     * @param jiraKey          Jira issue key (e.g. "INC-1234")
     * @param impactStartedAt  impact start time (used as startedAt for MTTR)
     * @param impactEndedAt    impact end time (used as resolvedAt for MTTR)
     */
    @Transactional
    public void upsertIncidentFromJira(Long workspaceId,
                                        Long trackedProjectId,
                                        String projectName,
                                        String jiraKey,
                                        Instant impactStartedAt,
                                        Instant impactEndedAt) {
        String idempotencyKey = "jira-" + jiraKey + "-" + trackedProjectId;
        Long serviceId = resolveOrCreateServiceWithMapping(workspaceId, projectName,
            SOURCE_TYPE_JIRA, String.valueOf(trackedProjectId));

        incidentEventRepository.findByWorkspaceIdAndIdempotencyKey(workspaceId, idempotencyKey)
            .ifPresentOrElse(
                existing -> {
                    existing.setStartedAt(impactStartedAt != null ? impactStartedAt : existing.getStartedAt());
                    existing.setResolvedAt(impactEndedAt);
                    incidentEventRepository.save(existing);
                },
                () -> incidentEventRepository.save(DoraIncidentEvent.builder()
                    .workspaceId(workspaceId)
                    .doraServiceId(serviceId)
                    .startedAt(impactStartedAt != null ? impactStartedAt : Instant.now())
                    .resolvedAt(impactEndedAt)
                    .source(IncidentSource.JIRA)
                    .idempotencyKey(idempotencyKey)
                    .externalId(jiraKey)
                    .build())
            );
    }

    /**
     * Resolves a service by name (case-insensitive). Creates it if it does not exist.
     * Returns null only if the name is blank (should not happen — validated upstream).
     */
    private Long resolveOrCreateService(Long workspaceId, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return doraServiceRepository
            .findByWorkspaceIdAndNameIgnoreCase(workspaceId, name.strip())
            .map(DoraService::getId)
            .orElseGet(() -> {
                DoraService created = doraServiceRepository.save(
                    DoraService.builder()
                        .workspaceId(workspaceId)
                        .name(name.strip())
                        .build());
                log.info("Auto-created DoraService name='{}' workspace={}", name, workspaceId);
                return created.getId();
            });
    }

    /**
     * Resolves or creates a DoraService by name, then ensures a source mapping exists.
     * The mapping links an external source key (e.g. trackedProjectId) to the service.
     */
    private Long resolveOrCreateServiceWithMapping(Long workspaceId,
                                                    String serviceName,
                                                    String sourceType,
                                                    String sourceKey) {
        Long serviceId = resolveOrCreateService(workspaceId, serviceName);
        if (serviceId == null) {
            return null;
        }
        doraServiceMappingRepository
            .findBySourceTypeAndSourceKey(sourceType, sourceKey)
            .ifPresentOrElse(
                existing -> { /* mapping already exists */ },
                () -> doraServiceMappingRepository.save(
                    DoraServiceMapping.builder()
                        .doraServiceId(serviceId)
                        .sourceType(sourceType)
                        .sourceKey(sourceKey)
                        .build())
            );
        return serviceId;
    }

    @SuppressWarnings("checkstyle:IllegalCatch")
    private String serializeMetadata(Object metadata) {
        if (metadata == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            log.warn("Could not serialize DORA event metadata", e);
            return null;
        }
    }
}
