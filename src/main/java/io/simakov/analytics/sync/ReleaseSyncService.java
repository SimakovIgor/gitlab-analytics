package io.simakov.analytics.sync;

import io.simakov.analytics.domain.model.GitSource;
import io.simakov.analytics.domain.model.MergeRequest;
import io.simakov.analytics.domain.model.ReleaseTag;
import io.simakov.analytics.domain.model.TrackedProject;
import io.simakov.analytics.domain.model.enums.MrState;
import io.simakov.analytics.domain.repository.GitSourceRepository;
import io.simakov.analytics.domain.repository.MergeRequestRepository;
import io.simakov.analytics.domain.repository.ReleaseTagRepository;
import io.simakov.analytics.domain.repository.TrackedProjectRepository;
import io.simakov.analytics.dora.DoraEventService;
import io.simakov.analytics.encryption.EncryptionService;
import io.simakov.analytics.gitlab.client.GitLabApiClient;
import io.simakov.analytics.gitlab.dto.GitLabPipelineDto;
import io.simakov.analytics.gitlab.dto.GitLabPipelineJobDto;
import io.simakov.analytics.gitlab.dto.GitLabReleaseDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Syncs GitLab releases (tags) for all tracked projects and attributes MRs to releases.
 *
 * <p>How it works:
 * <ol>
 *   <li>Fetch all releases from GitLab Releases API.</li>
 *   <li>For each release, find the tag pipeline and look for prod::deploy::* jobs.</li>
 *   <li>The earliest successful prod deploy job's finished_at = prod_deployed_at.</li>
 *   <li>Attribute each merged MR to the release: MR is in release N if
 *       {@code mr.merged_at} is between {@code tag(N-1).created_at} and {@code tag(N).created_at}.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReleaseSyncService {

    private static final String PROD_DEPLOY_STAGE_PREFIX = "prod";
    private static final String PROD_DEPLOY_JOB_PREFIX = "prod::deploy";
    private static final String JOB_STATUS_SUCCESS = "success";

    private final GitLabApiClient gitLabApiClient;
    private final EncryptionService encryptionService;
    private final DoraEventService doraEventService;

    private final TrackedProjectRepository trackedProjectRepository;
    private final GitSourceRepository gitSourceRepository;
    private final ReleaseTagRepository releaseTagRepository;
    private final MergeRequestRepository mergeRequestRepository;

    /**
     * Async fire-and-forget wrapper — used by the manual trigger endpoint.
     * Runs in syncTaskExecutor; does NOT create a tracked SyncJob.
     */
    @Async("syncTaskExecutor")
    public void syncReleasesForWorkspaceAsync(Long workspaceId) {
        syncReleasesForWorkspace(workspaceId);
    }

    /**
     * Sync releases for all enabled projects in the given workspace.
     */
    public void syncReleasesForWorkspace(Long workspaceId) {
        List<TrackedProject> projects = trackedProjectRepository
            .findAllByWorkspaceIdAndEnabledTrue(workspaceId);
        for (TrackedProject project : projects) {
            try {
                syncReleasesForProject(project);
            } catch (Exception e) {
                log.error("Release sync failed for project='{}': {}", project.getName(), e.getMessage(), e);
            }
        }
    }

    @Transactional
    public void syncReleasesForProject(TrackedProject project) {
        GitSource source = gitSourceRepository.findById(project.getGitSourceId())
            .orElseThrow(() -> new IllegalArgumentException("GitSource not found: " + project.getGitSourceId()));

        String baseUrl = source.getBaseUrl();
        String token = encryptionService.decrypt(project.getTokenEncrypted());
        Long gitlabProjectId = project.getGitlabProjectId();
        Long trackedProjectId = project.getId();

        log.info("Syncing releases for project='{}' (gitlabId={})", project.getName(), gitlabProjectId);

        List<GitLabReleaseDto> releases = gitLabApiClient.getReleases(baseUrl, token, gitlabProjectId);
        if (releases.isEmpty()) {
            log.debug("No releases found for project='{}'", project.getName());
            return;
        }

        log.info("Found {} release(s) for project='{}'", releases.size(), project.getName());

        // Upsert each release tag
        for (GitLabReleaseDto dto : releases) {
            upsertReleaseTag(dto, project, baseUrl, token, gitlabProjectId);
        }

        // Remove stale tags that no longer exist in GitLab (e.g. left over from a
        // previously tracked project that had the same tracked_project_id).
        Set<String> apiTagNames = releases.stream()
            .map(GitLabReleaseDto::tagName)
            .collect(Collectors.toSet());
        List<ReleaseTag> stale = releaseTagRepository
            .findAllByTrackedProjectIdOrderByTagCreatedAtDesc(trackedProjectId)
            .stream()
            .filter(t -> !apiTagNames.contains(t.getTagName()))
            .toList();
        if (!stale.isEmpty()) {
            log.info("Removing {} stale release tag(s) for project='{}': {}",
                stale.size(), project.getName(),
                stale.stream().map(ReleaseTag::getTagName).toList());
            releaseTagRepository.deleteAll(stale);
        }

        // Clear all existing attributions before re-attributing.
        // Without this, MRs merged after the newest tag keep a stale releaseTagId
        // from a previous sync run and never get updated.
        mergeRequestRepository.clearReleaseTagId(trackedProjectId);

        // Re-load all tags and sort by effectiveWindowTime DESC.
        // GitLab Releases can be created retroactively, so tagCreatedAt may be much later
        // than the actual prod deployment. Sorting by effectiveWindowTime = MIN(tagCreatedAt,
        // prodDeployedAt) puts tags in true chronological order and produces correct windows.
        List<ReleaseTag> allTags = releaseTagRepository
            .findAllByTrackedProjectIdOrderByTagCreatedAtDesc(trackedProjectId)
            .stream()
            .sorted(Comparator.comparing(this::effectiveWindowTime).reversed())
            .toList();

        attributeMrsToReleases(trackedProjectId, allTags);
        log.info("Release sync complete for project='{}'", project.getName());
    }

    private void upsertReleaseTag(GitLabReleaseDto dto,
                                  TrackedProject project,
                                  String baseUrl,
                                  String token,
                                  Long gitlabProjectId) {
        Long trackedProjectId = project.getId();
        Optional<ReleaseTag> existing = releaseTagRepository
            .findByTrackedProjectIdAndTagName(trackedProjectId, dto.tagName());

        ReleaseTag tag = existing.orElseGet(() -> ReleaseTag.builder()
            .trackedProjectId(trackedProjectId)
            .tagName(dto.tagName())
            .build());

        Instant tagCreatedAt = dto.createdAt() != null
            ? dto.createdAt()
            : dto.releasedAt();
        tag.setTagCreatedAt(tagCreatedAt);
        tag.setReleasedAt(dto.releasedAt());

        // Only look up pipeline and prod deploy if not already resolved
        if (tag.getProdDeployedAt() == null) {
            resolveProdDeployment(tag, dto.tagName(), baseUrl, token, gitlabProjectId);
        }

        releaseTagRepository.save(tag);

        // Mirror to universal DORA event store if this tag has a known prod deployment.
        if (tag.getProdDeployedAt() != null) {
            doraEventService.upsertDeployFromGitLab(
                project.getWorkspaceId(),
                trackedProjectId,
                project.getName(),
                dto.tagName(),
                tag.getProdDeployedAt());
        }
    }

    private void resolveProdDeployment(ReleaseTag tag,
                                       String tagName,
                                       String baseUrl,
                                       String token,
                                       Long gitlabProjectId) {
        List<GitLabPipelineDto> pipelines = gitLabApiClient
            .getPipelinesForRef(baseUrl, token, gitlabProjectId, tagName);

        boolean found = false;
        // Try each pipeline (most recent first) until we find prod::deploy jobs
        for (int i = 0; i < pipelines.size() && !found; i++) {
            GitLabPipelineDto pipeline = pipelines.get(i);
            List<GitLabPipelineJobDto> jobs = gitLabApiClient
                .getPipelineJobs(baseUrl, token, gitlabProjectId, pipeline.id());

            Optional<Instant> prodDeployedAt = jobs.stream()
                .filter(j -> JOB_STATUS_SUCCESS.equals(j.status()))
                .filter(j -> j.name() != null && j.name().startsWith(PROD_DEPLOY_JOB_PREFIX))
                .map(GitLabPipelineJobDto::finishedAt)
                .filter(t -> t != null)
                .min(Comparator.naturalOrder());

            Optional<Instant> stageDeployedAt = jobs.stream()
                .filter(j -> JOB_STATUS_SUCCESS.equals(j.status()))
                .filter(j -> j.stage() != null && j.stage().startsWith(PROD_DEPLOY_STAGE_PREFIX)
                    && (j.name() == null || !j.name().startsWith(PROD_DEPLOY_JOB_PREFIX)))
                .map(GitLabPipelineJobDto::finishedAt)
                .filter(t -> t != null)
                .min(Comparator.naturalOrder());

            if (prodDeployedAt.isPresent() || stageDeployedAt.isPresent()) {
                tag.setPipelineId(pipeline.id());
                prodDeployedAt.ifPresent(tag::setProdDeployedAt);
                stageDeployedAt.ifPresent(tag::setStageDeployedAt);
                log.info("Tag '{}': pipelineId={}, stageDeployedAt={}, prodDeployedAt={}",
                    tagName, pipeline.id(), tag.getStageDeployedAt(), tag.getProdDeployedAt());
                found = true;
            }
        }

        if (!found) {
            log.debug("No prod::deploy jobs found for tag '{}' in any pipeline (checked {} pipeline(s))",
                tagName, pipelines.size());
        }
    }

    /**
     * Attribute each merged MR to the release it shipped in.
     * MR belongs to release N if mr.merged_at is between prev_tag.created_at and tag_N.created_at.
     * Tags are ordered newest first; we iterate to build windows.
     *
     * <p>If a tag was never deployed to prod (prod_deployed_at is null — e.g. a skipped release),
     * its MRs are re-attributed to the nearest newer deployed tag so that Lead Time is calculated
     * correctly. If no newer deployed tag exists yet, MRs stay on the undeployed tag and are
     * naturally excluded from Lead Time by the SQL filter {@code rt.prod_deployed_at IS NOT NULL}.
     */
    private void attributeMrsToReleases(Long trackedProjectId,
                                        List<ReleaseTag> tagsNewestFirst) {
        if (tagsNewestFirst.isEmpty()) {
            return;
        }

        List<MergeRequest> allMrs = mergeRequestRepository
            .findOpenByProjectIds(List.of(trackedProjectId), MrState.MERGED);

        // Build windows: [prevTagCreatedAt, tagCreatedAt) for each release
        // tagsNewestFirst: index 0 = newest, index 1 = second newest, etc.
        for (int i = 0; i < tagsNewestFirst.size(); i++) {
            attributeMrsToRelease(i, tagsNewestFirst, allMrs);
        }
    }

    private void attributeMrsToRelease(int index,
                                       List<ReleaseTag> tagsNewestFirst,
                                       List<MergeRequest> allMrs) {
        ReleaseTag tag = tagsNewestFirst.get(index);
        Instant windowEnd = effectiveWindowTime(tag);
        Instant windowStart = (index + 1 < tagsNewestFirst.size())
            ? effectiveWindowTime(tagsNewestFirst.get(index + 1))
            : Instant.EPOCH;

        // If this tag was never deployed, find the nearest newer deployed tag.
        // MRs in a skipped release actually shipped in that later deployment.
        boolean isRedirected = tag.getProdDeployedAt() == null;
        ReleaseTag effectiveTag = isRedirected
            ? findNearestNewerDeployedTag(tagsNewestFirst, index)
            : tag;
        if (effectiveTag == null) {
            // No deployed tag ahead yet — keep natural attribution so the release
            // table still shows these MRs, but Lead Time won't count them (no prod_deployed_at).
            effectiveTag = tag;
            isRedirected = false;
        }

        List<MergeRequest> mrsInRelease = allMrs.stream()
            .filter(mr -> mr.getMergedAtGitlab() != null)
            .filter(mr -> !mr.getMergedAtGitlab().isBefore(windowStart)
                && mr.getMergedAtGitlab().isBefore(windowEnd))
            .toList();

        for (MergeRequest mr : mrsInRelease) {
            mr.setReleaseTagId(effectiveTag.getId());
        }
        if (!mrsInRelease.isEmpty()) {
            mergeRequestRepository.saveAll(mrsInRelease);
            if (isRedirected) {
                log.debug("Tag '{}' (skipped, no prod deploy) → re-attributed {} MR(s) to '{}' (window {} → {})",
                    tag.getTagName(), mrsInRelease.size(), effectiveTag.getTagName(), windowStart, windowEnd);
            } else {
                log.debug("Tag '{}': attributed {} MR(s) (window {} → {})",
                    tag.getTagName(), mrsInRelease.size(), windowStart, windowEnd);
            }
        }
    }

    /**
     * Effective window boundary for a tag.
     * Normally this is {@code tag.tagCreatedAt}. But when a GitLab Release is created
     * retroactively (prod was deployed before the release entry was registered in GitLab),
     * {@code prodDeployedAt < tagCreatedAt}. In that case we use {@code prodDeployedAt}
     * as the boundary so that MRs merged after the actual deployment are not pulled into
     * this release and do not get a negative Lead Time.
     */
    private Instant effectiveWindowTime(ReleaseTag tag) {
        if (tag.getProdDeployedAt() != null && tag.getProdDeployedAt().isBefore(tag.getTagCreatedAt())) {
            return tag.getProdDeployedAt();
        }
        return tag.getTagCreatedAt();
    }

    /**
     * Finds the nearest newer (higher in the timeline) deployed tag for a skipped release.
     * In a newest-first list, "newer" means a lower index.
     * Returns null if no deployed tag exists ahead of the given index.
     */
    private ReleaseTag findNearestNewerDeployedTag(List<ReleaseTag> tagsNewestFirst,
                                                   int fromIndex) {
        for (int j = fromIndex - 1; j >= 0; j--) {
            if (tagsNewestFirst.get(j).getProdDeployedAt() != null) {
                return tagsNewestFirst.get(j);
            }
        }
        return null;
    }
}
