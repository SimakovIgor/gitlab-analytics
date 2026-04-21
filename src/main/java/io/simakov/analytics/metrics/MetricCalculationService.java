package io.simakov.analytics.metrics;

import io.simakov.analytics.domain.model.MergeRequest;
import io.simakov.analytics.domain.model.MergeRequestApproval;
import io.simakov.analytics.domain.model.MergeRequestCommit;
import io.simakov.analytics.domain.model.MergeRequestNote;
import io.simakov.analytics.domain.model.TrackedUser;
import io.simakov.analytics.domain.model.TrackedUserAlias;
import io.simakov.analytics.domain.repository.MergeRequestApprovalRepository;
import io.simakov.analytics.domain.repository.MergeRequestCommitRepository;
import io.simakov.analytics.domain.repository.MergeRequestNoteRepository;
import io.simakov.analytics.domain.repository.MergeRequestRepository;
import io.simakov.analytics.domain.repository.TrackedUserAliasRepository;
import io.simakov.analytics.domain.repository.TrackedUserRepository;
import io.simakov.analytics.metrics.model.UserMetrics;
import io.simakov.analytics.metrics.provider.MetricProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Facade that loads data from repositories and delegates metric computation
 * to the registered {@link MetricProvider} implementations.
 *
 * <p>Spring collects all {@code MetricProvider} beans in {@link org.springframework.core.annotation.Order}
 * order and injects them as {@code List<MetricProvider>}. To add a new metric group,
 * create a new {@code @Component} that implements {@code MetricProvider} — no changes here needed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetricCalculationService {

    private final MergeRequestRepository mrRepository;
    private final MergeRequestNoteRepository noteRepository;
    private final MergeRequestApprovalRepository approvalRepository;
    private final MergeRequestCommitRepository commitRepository;
    private final TrackedUserRepository trackedUserRepository;
    private final TrackedUserAliasRepository aliasRepository;
    private final List<MetricProvider> metricProviders;

    /**
     * Calculate metrics for all requested users over the given period.
     *
     * @param projectIds tracked project IDs to include
     * @param userIds    tracked user IDs to calculate metrics for
     * @param dateFrom   start of period (inclusive)
     * @param dateTo     end of period (inclusive)
     * @return map of trackedUserId -> UserMetrics
     */
    @Transactional(readOnly = true)
    public Map<Long, UserMetrics> calculate(List<Long> projectIds,
                                            List<Long> userIds,
                                            Instant dateFrom,
                                            Instant dateTo) {
        List<MergeRequest> allMrs = mrRepository.findMergedInPeriod(projectIds, dateFrom, dateTo);
        if (allMrs.isEmpty()) {
            log.info("No MRs found for projects {} in period {} - {}", projectIds, dateFrom, dateTo);
        }

        List<Long> mrIds = allMrs.stream().map(MergeRequest::getId).toList();

        // Batch-load all related data to avoid N+1
        Map<Long, List<MergeRequestNote>> notesByMrId = noteRepository.findByMergeRequestIdIn(mrIds)
            .stream().collect(Collectors.groupingBy(MergeRequestNote::getMergeRequestId));
        Map<Long, List<MergeRequestApproval>> approvalsByMrId = approvalRepository.findByMergeRequestIdIn(mrIds)
            .stream().collect(Collectors.groupingBy(MergeRequestApproval::getMergeRequestId));
        Map<Long, List<MergeRequestCommit>> commitsByMrId = commitRepository.findByMergeRequestIdIn(mrIds)
            .stream().collect(Collectors.groupingBy(MergeRequestCommit::getMergeRequestId));

        Map<Long, AliasData> aliasDataByUser = resolveAliasData(userIds);
        Map<Long, TrackedUser> usersById = trackedUserRepository.findAllById(userIds)
            .stream().collect(Collectors.toMap(TrackedUser::getId, u -> u));

        Map<Long, UserMetrics> results = new LinkedHashMap<>();
        for (Long userId : userIds) {
            TrackedUser user = usersById.get(userId);
            if (user == null) {
                continue;
            }
            AliasData alias = aliasDataByUser.getOrDefault(userId, AliasData.empty());
            if (alias.gitlabIds().isEmpty() && alias.emails().isEmpty() && user.getEmail() == null) {
                log.warn("TrackedUser {} has no email and no aliases -- metrics will be empty", userId);
            }

            Set<String> aliasEmails = buildAliasEmails(user, alias);
            List<MergeRequest> authoredMrs = resolveAuthoredMrs(
                alias.gitlabIds(), aliasEmails, allMrs, commitsByMrId);
            List<MergeRequestCommit> userCommits = collectUserCommits(
                authoredMrs, commitsByMrId, user, aliasEmails);

            MetricContext ctx = new MetricContext(
                user, aliasEmails, alias.gitlabIds(),
                authoredMrs, userCommits,
                notesByMrId, approvalsByMrId, commitsByMrId
            );

            UserMetrics.UserMetricsBuilder builder = UserMetrics.builder()
                .trackedUserId(user.getId())
                .displayName(user.getDisplayName());

            metricProviders.forEach(p -> p.populate(ctx, builder));
            results.put(userId, builder.build());
        }
        return results;
    }

    // ---- Data preparation helpers ------------------------------------------

    private Set<String> buildAliasEmails(TrackedUser user,
                                         AliasData alias) {
        Set<String> emails = new HashSet<>(alias.emails());
        if (user.getEmail() != null && !user.getEmail().isBlank()) {
            emails.add(user.getEmail().toLowerCase(Locale.ROOT));
        }
        return emails;
    }

    private List<MergeRequest> resolveAuthoredMrs(Set<Long> gitlabIds,
                                                  Set<String> aliasEmails,
                                                  List<MergeRequest> allMrs,
                                                  Map<Long, List<MergeRequestCommit>> commitsByMrId) {
        Set<Long> authoredMrIds = resolveAuthoredMrIds(gitlabIds, aliasEmails, allMrs, commitsByMrId);
        return allMrs.stream()
            .filter(mr -> authoredMrIds.contains(mr.getId()))
            .toList();
    }

    /**
     * MR attribution: by author_gitlab_user_id (primary) or by first commit email (fallback).
     */
    private Set<Long> resolveAuthoredMrIds(Set<Long> gitlabIds,
                                           Set<String> aliasEmails,
                                           List<MergeRequest> allMrs,
                                           Map<Long, List<MergeRequestCommit>> commitsByMrId) {
        if (!gitlabIds.isEmpty()) {
            return allMrs.stream()
                .filter(mr -> gitlabIds.contains(mr.getAuthorGitlabUserId()))
                .map(MergeRequest::getId)
                .collect(Collectors.toSet());
        }
        // Fallback: no GitLab user ID -- identify by earliest commit email in each MR
        return commitsByMrId.entrySet().stream()
            .filter(e -> {
                MergeRequestCommit first = e.getValue().stream()
                    .filter(c -> c.getAuthoredDate() != null)
                    .min(Comparator.comparing(MergeRequestCommit::getAuthoredDate))
                    .orElse(null);
                return first != null
                    && first.getAuthorEmail() != null
                    && aliasEmails.contains(first.getAuthorEmail().toLowerCase(Locale.ROOT));
            })
            .map(Map.Entry::getKey)
            .collect(Collectors.toSet());
    }

    /**
     * Deduplicated (by SHA) commits authored by the user in their MRs.
     * One SHA can appear in multiple MRs (squash chains) -- deduplication prevents double-counting.
     */
    private List<MergeRequestCommit> collectUserCommits(List<MergeRequest> authoredMrs,
                                                        Map<Long, List<MergeRequestCommit>> commitsByMrId,
                                                        TrackedUser user,
                                                        Set<String> aliasEmails) {
        Set<Long> authoredMrIds = authoredMrs.stream()
            .map(MergeRequest::getId)
            .collect(Collectors.toSet());
        return commitsByMrId.entrySet().stream()
            .filter(e -> authoredMrIds.contains(e.getKey()))
            .flatMap(e -> e.getValue().stream())
            .filter(c -> isUserCommit(c, user, aliasEmails))
            .collect(Collectors.toMap(
                MergeRequestCommit::getGitlabCommitSha,
                c -> c,
                (a, b) -> a
            ))
            .values().stream().toList();
    }

    private boolean isUserCommit(MergeRequestCommit commit,
                                 TrackedUser user,
                                 Set<String> aliasEmails) {
        if (commit.getAuthorEmail() == null) {
            return false;
        }
        String email = commit.getAuthorEmail().toLowerCase(Locale.ROOT);
        return user.getEmail() != null && user.getEmail().equalsIgnoreCase(email)
            || aliasEmails.contains(email);
    }

    /**
     * Loads all aliases in one batch to avoid N+1 per user.
     */
    private Map<Long, AliasData> resolveAliasData(List<Long> userIds) {
        List<TrackedUserAlias> aliases = aliasRepository.findByTrackedUserIdIn(userIds);
        Map<Long, Set<Long>> gitlabIdsByUser = aliases.stream()
            .filter(a -> a.getGitlabUserId() != null)
            .collect(Collectors.groupingBy(
                TrackedUserAlias::getTrackedUserId,
                Collectors.mapping(TrackedUserAlias::getGitlabUserId, Collectors.toSet())
            ));
        Map<Long, Set<String>> emailsByUser = aliases.stream()
            .filter(a -> a.getEmail() != null)
            .collect(Collectors.groupingBy(
                TrackedUserAlias::getTrackedUserId,
                Collectors.mapping(a -> a.getEmail().toLowerCase(Locale.ROOT), Collectors.toSet())
            ));
        return userIds.stream().collect(Collectors.toMap(
            id -> id,
            id -> new AliasData(
                gitlabIdsByUser.getOrDefault(id, Set.of()),
                emailsByUser.getOrDefault(id, Set.of())
            )
        ));
    }

    private record AliasData(Set<Long> gitlabIds, Set<String> emails) {

        static AliasData empty() {
            return new AliasData(Set.of(), Set.of());
        }
    }
}
