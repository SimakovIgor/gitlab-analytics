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
import io.simakov.analytics.encryption.EncryptionService;
import io.simakov.analytics.gitlab.client.GitLabApiClient;
import io.simakov.analytics.gitlab.dto.GitLabPipelineDto;
import io.simakov.analytics.gitlab.dto.GitLabPipelineJobDto;
import io.simakov.analytics.gitlab.dto.GitLabReleaseDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

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

    private final TrackedProjectRepository trackedProjectRepository;
    private final GitSourceRepository gitSourceRepository;
    private final ReleaseTagRepository releaseTagRepository;
    private final MergeRequestRepository mergeRequestRepository;

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
            upsertReleaseTag(dto, trackedProjectId, baseUrl, token, gitlabProjectId);
        }

        // Re-load all tags ordered by creation time to attribute MRs
        List<ReleaseTag> allTags = releaseTagRepository
            .findAllByTrackedProjectIdOrderByTagCreatedAtDesc(trackedProjectId);

        attributeMrsToReleases(trackedProjectId, allTags);
        log.info("Release sync complete for project='{}'", project.getName());
    }

    private void upsertReleaseTag(GitLabReleaseDto dto,
                                  Long trackedProjectId,
                                  String baseUrl,
                                  String token,
                                  Long gitlabProjectId) {
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
            ReleaseTag tag = tagsNewestFirst.get(i);
            Instant windowEnd = tag.getTagCreatedAt();
            Instant windowStart = (i + 1 < tagsNewestFirst.size())
                ? tagsNewestFirst.get(i + 1).getTagCreatedAt()
                : Instant.EPOCH;

            final Instant start = windowStart;
            final Instant end = windowEnd;

            List<MergeRequest> mrsInRelease = allMrs.stream()
                .filter(mr -> mr.getMergedAtGitlab() != null)
                .filter(mr -> !mr.getMergedAtGitlab().isBefore(start)
                    && mr.getMergedAtGitlab().isBefore(end))
                .toList();

            for (MergeRequest mr : mrsInRelease) {
                mr.setReleaseTagId(tag.getId());
            }
            if (!mrsInRelease.isEmpty()) {
                mergeRequestRepository.saveAll(mrsInRelease);
                log.debug("Tag '{}': attributed {} MR(s) (window {} → {})",
                    tag.getTagName(), mrsInRelease.size(), start, end);
            }
        }
    }
}
