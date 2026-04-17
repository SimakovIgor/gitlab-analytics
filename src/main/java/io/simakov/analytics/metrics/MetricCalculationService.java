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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

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

    /**
     * Calculate metrics for all requested users over the given period.
     *
     * @param projectIds tracked project IDs to include
     * @param userIds    tracked user IDs to calculate metrics for
     * @param dateFrom   start of period (inclusive)
     * @param dateTo     end of period (inclusive)
     * @return map of trackedUserId → UserMetrics
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

        // Load all related data in bulk to avoid N+1
        Map<Long, List<MergeRequestNote>> notesByMrId = noteRepository.findByMergeRequestIdIn(mrIds)
            .stream().collect(Collectors.groupingBy(MergeRequestNote::getMergeRequestId));
        Map<Long, List<MergeRequestApproval>> approvalsByMrId = approvalRepository.findByMergeRequestIdIn(mrIds)
            .stream().collect(Collectors.groupingBy(MergeRequestApproval::getMergeRequestId));
        Map<Long, List<MergeRequestCommit>> commitsByMrId = commitRepository.findByMergeRequestIdIn(mrIds)
            .stream().collect(Collectors.groupingBy(MergeRequestCommit::getMergeRequestId));

        Map<Long, AliasData> aliasDataByUser = resolveAliasData(userIds);

        Map<Long, UserMetrics> results = new LinkedHashMap<>();
        for (Long userId : userIds) {
            TrackedUser user = trackedUserRepository.findById(userId).orElse(null);
            if (user == null) {
                continue;
            }
            AliasData alias = aliasDataByUser.getOrDefault(userId, AliasData.empty());
            if (alias.gitlabIds().isEmpty()) {
                log.warn("TrackedUser {} has no GitLab aliases — metrics will be empty", userId);
            }
            results.put(userId, calculateForUser(user, alias, allMrs, notesByMrId, approvalsByMrId, commitsByMrId));
        }
        return results;
    }

    private UserMetrics calculateForUser(TrackedUser user,
                                         AliasData alias,
                                         List<MergeRequest> allMrs,
                                         Map<Long, List<MergeRequestNote>> notesByMrId,
                                         Map<Long, List<MergeRequestApproval>> approvalsByMrId,
                                         Map<Long, List<MergeRequestCommit>> commitsByMrId) {
        Set<Long> gitlabIds = alias.gitlabIds();
        Set<String> aliasEmails = alias.emails();

        // --- Authored MRs ---
        List<MergeRequest> authoredMrs = allMrs.stream()
            .filter(mr -> gitlabIds.contains(mr.getAuthorGitlabUserId()))
            .toList();
        int mrMergedCount = (int) authoredMrs.stream().filter(mr -> mr.getMergedAtGitlab() != null).count();
        Set<Long> authoredMrIds = authoredMrs.stream().map(MergeRequest::getId).collect(Collectors.toSet());
        Set<Long> projectsTouched = authoredMrs.stream()
            .map(MergeRequest::getTrackedProjectId).collect(Collectors.toSet());

        // --- Commits & volume ---
        List<MergeRequestCommit> userCommits = commitsByMrId.entrySet().stream()
            .filter(e -> authoredMrIds.contains(e.getKey()))
            .flatMap(e -> e.getValue().stream())
            .filter(c -> isUserCommit(c, user, aliasEmails))
            .toList();
        ChangeVolume volume = computeChangeVolume(userCommits, authoredMrs, commitsByMrId);

        // --- Notes & approvals ---
        List<MergeRequestNote> userNotes = notesByMrId.values().stream()
            .flatMap(Collection::stream)
            .filter(n -> gitlabIds.contains(n.getAuthorGitlabUserId()))
            .toList();
        List<MergeRequestApproval> userApprovals = approvalsByMrId.values().stream()
            .flatMap(Collection::stream)
            .filter(a -> gitlabIds.contains(a.getApprovedByGitlabUserId()))
            .toList();

        int activeDaysCount = collectActiveDays(userCommits, userNotes, userApprovals).size();

        // --- Review contribution ---
        List<MergeRequestNote> reviewNotes = userNotes.stream()
            .filter(n -> !n.isSystem() && !authoredMrIds.contains(n.getMergeRequestId()))
            .toList();
        Set<Long> reviewedMrIds = reviewNotes.stream()
            .map(MergeRequestNote::getMergeRequestId)
            .collect(Collectors.toCollection(HashSet::new));
        userApprovals.stream()
            .map(MergeRequestApproval::getMergeRequestId)
            .filter(id -> !authoredMrIds.contains(id))
            .forEach(reviewedMrIds::add);
        int mrsReviewedCount = reviewedMrIds.size();
        int approvalsGivenCount = (int) userApprovals.stream()
            .filter(a -> !authoredMrIds.contains(a.getMergeRequestId())).count();

        // --- Flow ---
        FlowResult flow = computeFlowMetrics(authoredMrs, gitlabIds, user, aliasEmails,
            notesByMrId, approvalsByMrId, commitsByMrId);

        double reworkRatio = mrMergedCount > 0 ? (double) flow.reworkMrCount() / mrMergedCount : 0.0;
        double selfMergeRatio = mrMergedCount > 0 ? (double) flow.selfMergeCount() / mrMergedCount : 0.0;
        double mrMergedPerActiveDay = activeDaysCount > 0 ? (double) mrMergedCount / activeDaysCount : 0.0;
        double commentsPerReviewedMr = mrsReviewedCount > 0
            ? (double) reviewNotes.size() / mrsReviewedCount : 0.0;

        return UserMetrics.builder()
            .trackedUserId(user.getId())
            .displayName(user.getDisplayName())
            // Delivery
            .mrOpenedCount(authoredMrs.size())
            .mrMergedCount(mrMergedCount)
            .activeDaysCount(activeDaysCount)
            .repositoriesTouchedCount(projectsTouched.size())
            .commitsInMrCount(userCommits.size())
            // Change volume
            .linesAdded(volume.linesAdded())
            .linesDeleted(volume.linesDeleted())
            .linesChanged(volume.linesAdded() + volume.linesDeleted())
            .filesChanged(volume.filesChanged())
            .avgMrSizeLines(volume.avgMrSizeLines())
            .medianMrSizeLines(volume.medianMrSizeLines())
            .avgMrSizeFiles(volume.avgMrSizeFiles())
            // Review
            .reviewCommentsWrittenCount(reviewNotes.size())
            .mrsReviewedCount(mrsReviewedCount)
            .approvalsGivenCount(approvalsGivenCount)
            .reviewThreadsStartedCount((int) countReviewThreadsStarted(reviewNotes, notesByMrId))
            // Flow
            .avgTimeToFirstReviewMinutes(optMean(flow.timeToFirstReview()))
            .medianTimeToFirstReviewMinutes(optMedian(flow.timeToFirstReview()))
            .avgTimeToMergeMinutes(optMean(flow.timeToMerge()))
            .medianTimeToMergeMinutes(optMedian(flow.timeToMerge()))
            .reworkMrCount(flow.reworkMrCount())
            .reworkRatio(round2(reworkRatio))
            .selfMergeCount(flow.selfMergeCount())
            .selfMergeRatio(round2(selfMergeRatio))
            // Normalized
            .mrMergedPerActiveDay(round2(mrMergedPerActiveDay))
            .commentsPerReviewedMr(round2(commentsPerReviewedMr))
            .build();
    }

    private ChangeVolume computeChangeVolume(List<MergeRequestCommit> userCommits,
                                             List<MergeRequest> authoredMrs,
                                             Map<Long, List<MergeRequestCommit>> commitsByMrId) {
        int linesAdded = userCommits.stream().mapToInt(MergeRequestCommit::getAdditions).sum();
        int linesDeleted = userCommits.stream().mapToInt(MergeRequestCommit::getDeletions).sum();
        int filesChanged = userCommits.stream().mapToInt(MergeRequestCommit::getFilesChangedCount).sum();

        // MR size in lines = all commits in the MR (not just user's), filtered for non-zero
        List<Integer> mrSizesLines = authoredMrs.stream()
            .map(mr -> commitsByMrId.getOrDefault(mr.getId(), List.of()).stream()
                .mapToInt(c -> c.getAdditions() + c.getDeletions()).sum())
            .filter(size -> size > 0)
            .sorted()
            .toList();

        // MR size in files = from MR entity (GitLab provides this directly)
        List<Integer> mrSizesFiles = authoredMrs.stream()
            .map(MergeRequest::getFilesChangedCount)
            .sorted()
            .toList();

        return new ChangeVolume(
            linesAdded, linesDeleted, filesChanged,
            round2(mrSizesLines.isEmpty() ? 0 : mean(mrSizesLines)),
            round2(mrSizesLines.isEmpty() ? 0 : median(mrSizesLines)),
            round2(mrSizesFiles.isEmpty() ? 0 : mean(mrSizesFiles))
        );
    }

    private Set<String> collectActiveDays(List<MergeRequestCommit> userCommits,
                                          List<MergeRequestNote> userNotes,
                                          List<MergeRequestApproval> userApprovals) {
        Set<String> activeDays = new HashSet<>();
        userCommits.stream().map(MergeRequestCommit::getAuthoredDate)
            .filter(Objects::nonNull).map(this::toDateString).forEach(activeDays::add);
        userNotes.stream().map(MergeRequestNote::getCreatedAtGitlab)
            .filter(Objects::nonNull).map(this::toDateString).forEach(activeDays::add);
        userApprovals.stream().map(MergeRequestApproval::getApprovedAtGitlab)
            .filter(Objects::nonNull).map(this::toDateString).forEach(activeDays::add);
        return activeDays;
    }

    private FlowResult computeFlowMetrics(List<MergeRequest> authoredMrs,
                                          Set<Long> gitlabIds,
                                          TrackedUser user,
                                          Set<String> aliasEmails,
                                          Map<Long, List<MergeRequestNote>> notesByMrId,
                                          Map<Long, List<MergeRequestApproval>> approvalsByMrId,
                                          Map<Long, List<MergeRequestCommit>> commitsByMrId) {
        List<Long> timeToFirstReview = new ArrayList<>();
        List<Long> timeToMerge = new ArrayList<>();
        int reworkMrCount = 0;
        int selfMergeCount = 0;

        for (MergeRequest mr : authoredMrs) {
            List<MergeRequestNote> mrNotes = notesByMrId.getOrDefault(mr.getId(), List.of());
            List<MergeRequestApproval> mrApprovals = approvalsByMrId.getOrDefault(mr.getId(), List.of());
            List<MergeRequestCommit> mrCommits = commitsByMrId.getOrDefault(mr.getId(), List.of());

            Optional<Instant> firstExternalReview = findFirstExternalReviewEvent(gitlabIds, mrNotes, mrApprovals);

            if (firstExternalReview.isPresent()) {
                long minutes = ChronoUnit.MINUTES.between(mr.getCreatedAtGitlab(), firstExternalReview.get());
                if (minutes >= 0) {
                    timeToFirstReview.add(minutes);
                }
                if (isReworked(mrCommits, user, aliasEmails, firstExternalReview.get())) {
                    reworkMrCount++;
                }
            }

            collectTimeToMerge(mr, timeToMerge);

            if (mr.getMergedByGitlabUserId() != null && gitlabIds.contains(mr.getMergedByGitlabUserId())) {
                selfMergeCount++;
            }
        }

        return new FlowResult(timeToFirstReview, timeToMerge, reworkMrCount, selfMergeCount);
    }

    private Optional<Instant> findFirstExternalReviewEvent(Set<Long> authorGitlabIds,
                                                           List<MergeRequestNote> notes,
                                                           List<MergeRequestApproval> approvals) {
        Optional<Instant> firstNote = notes.stream()
            .filter(n -> !n.isSystem())
            .filter(n -> n.getAuthorGitlabUserId() != null
                && !authorGitlabIds.contains(n.getAuthorGitlabUserId()))
            .map(MergeRequestNote::getCreatedAtGitlab)
            .filter(Objects::nonNull)
            .min(Instant::compareTo);

        Optional<Instant> firstApproval = approvals.stream()
            .filter(a -> a.getApprovedByGitlabUserId() != null
                && !authorGitlabIds.contains(a.getApprovedByGitlabUserId()))
            .map(MergeRequestApproval::getApprovedAtGitlab)
            .filter(Objects::nonNull)
            .min(Instant::compareTo);

        if (firstNote.isEmpty()) {
            return firstApproval;
        }
        return firstApproval
            .map(instant -> firstNote.get().isBefore(instant) ? firstNote.get() : instant)
            .or(() -> firstNote);
    }

    private long countReviewThreadsStarted(List<MergeRequestNote> reviewNotes,
                                           Map<Long, List<MergeRequestNote>> notesByMrId) {
        // A thread is "started" if this user's note has the earliest createdAt in its discussion
        Map<Long, Instant> firstNoteByDiscussion = new HashMap<>();
        notesByMrId.values().stream()
            .flatMap(Collection::stream)
            .filter(n -> n.getDiscussionId() != null && n.getCreatedAtGitlab() != null)
            .forEach(n -> firstNoteByDiscussion.merge(n.getDiscussionId(), n.getCreatedAtGitlab(),
                (a, b) -> a.isBefore(b) ? a : b));

        return reviewNotes.stream()
            .filter(n -> n.getDiscussionId() != null && n.getCreatedAtGitlab() != null)
            .filter(n -> n.getCreatedAtGitlab().equals(firstNoteByDiscussion.get(n.getDiscussionId())))
            .count();
    }

    private boolean isUserCommit(MergeRequestCommit commit, TrackedUser user, Set<String> aliasEmails) {
        if (commit.getAuthorEmail() == null) {
            return false;
        }
        String email = commit.getAuthorEmail().toLowerCase(Locale.ROOT);
        return user.getEmail() != null && user.getEmail().equalsIgnoreCase(email)
            || aliasEmails.contains(email);
    }

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

    private void collectTimeToMerge(MergeRequest mr, List<Long> timeToMerge) {
        if (mr.getMergedAtGitlab() == null) {
            return;
        }
        long minutes = ChronoUnit.MINUTES.between(mr.getCreatedAtGitlab(), mr.getMergedAtGitlab());
        if (minutes >= 0) {
            timeToMerge.add(minutes);
        }
    }

    private boolean isReworked(List<MergeRequestCommit> mrCommits,
                               TrackedUser user,
                               Set<String> aliasEmails,
                               Instant firstExternalReview) {
        return mrCommits.stream()
            .filter(c -> isUserCommit(c, user, aliasEmails))
            .filter(c -> c.getAuthoredDate() != null)
            .anyMatch(c -> c.getAuthoredDate().isAfter(firstExternalReview));
    }

    // -----------------------------------------------------------------------
    // Math helpers
    // -----------------------------------------------------------------------

    private Double optMean(List<Long> values) {
        return values.isEmpty() ? null : round2(mean(values));
    }

    private Double optMedian(List<Long> values) {
        return values.isEmpty() ? null : round2(median(values));
    }

    private double mean(List<? extends Number> values) {
        if (values.isEmpty()) {
            return 0;
        }
        return values.stream().mapToDouble(Number::doubleValue).average().orElse(0);
    }

    private double median(List<? extends Number> sorted) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int size = sorted.size();
        if (size % 2 == 1) {
            return sorted.get(size / 2).doubleValue();
        }
        return (sorted.get(size / 2 - 1).doubleValue() + sorted.get(size / 2).doubleValue()) / 2.0;
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private String toDateString(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC).toLocalDate().toString();
    }

    // -----------------------------------------------------------------------
    // Inner types
    // -----------------------------------------------------------------------

    private record AliasData(Set<Long> gitlabIds, Set<String> emails) {
        static AliasData empty() {
            return new AliasData(Set.of(), Set.of());
        }
    }

    private record ChangeVolume(int linesAdded,
                                int linesDeleted,
                                int filesChanged,
                                double avgMrSizeLines,
                                double medianMrSizeLines,
                                double avgMrSizeFiles) {
    }

    private record FlowResult(List<Long> timeToFirstReview,
                              List<Long> timeToMerge,
                              int reworkMrCount,
                              int selfMergeCount) {
    }
}
