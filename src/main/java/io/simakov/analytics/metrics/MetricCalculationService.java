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
import java.util.Collections;
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
    @SuppressWarnings({"CyclomaticComplexity", "JavaNCSS", "NPathComplexity"})
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

        // Resolve each tracked user's GitLab user IDs and commit emails
        Map<Long, Set<Long>> gitlabIdsByTrackedUser = resolveGitlabIds(userIds);
        Map<Long, Set<String>> aliasEmailsByTrackedUser = resolveAliasEmails(userIds);

        Map<Long, UserMetrics> results = new LinkedHashMap<>();
        for (Long userId : userIds) {
            TrackedUser user = trackedUserRepository.findById(userId).orElse(null);
            if (user == null) {
                continue;
            }

            Set<Long> gitlabIds = gitlabIdsByTrackedUser.getOrDefault(userId, Collections.emptySet());
            if (gitlabIds.isEmpty()) {
                log.warn("TrackedUser {} has no GitLab aliases — metrics will be empty", userId);
            }

            Set<String> aliasEmails = aliasEmailsByTrackedUser.getOrDefault(userId, Collections.emptySet());
            UserMetrics metrics = calculateForUser(user, gitlabIds, aliasEmails, allMrs,
                notesByMrId, approvalsByMrId, commitsByMrId);
            results.put(userId, metrics);
        }

        return results;
    }

    @SuppressWarnings({
        "MethodLength",
        "PMD.CyclomaticComplexity",
        "JavaNCSS",
        "PMD.NPathComplexity",
        "PMD.CognitiveComplexity"})
    private UserMetrics calculateForUser(TrackedUser user,
                                         Set<Long> gitlabIds,
                                         Set<String> aliasEmails,
                                         List<MergeRequest> allMrs,
                                         Map<Long, List<MergeRequestNote>> notesByMrId,
                                         Map<Long, List<MergeRequestApproval>> approvalsByMrId,
                                         Map<Long, List<MergeRequestCommit>> commitsByMrId) {

        // MRs authored by this user
        List<MergeRequest> authoredMrs = allMrs.stream()
            .filter(mr -> gitlabIds.contains(mr.getAuthorGitlabUserId()))
            .toList();

        // --- Delivery ---
        int mrOpenedCount = authoredMrs.size();
        int mrMergedCount = (int) authoredMrs.stream()
            .filter(mr -> mr.getMergedAtGitlab() != null)
            .count();

        Set<Long> authoredMrIds = authoredMrs.stream().map(MergeRequest::getId).collect(Collectors.toSet());
        Set<Long> projectsTouched = authoredMrs.stream()
            .map(MergeRequest::getTrackedProjectId)
            .collect(Collectors.toSet());

        // --- Commits in authored MRs (resolved first — needed for lines/size metrics below) ---
        List<MergeRequestCommit> userCommits = commitsByMrId.entrySet().stream()
            .filter(e -> authoredMrIds.contains(e.getKey()))
            .flatMap(e -> e.getValue().stream())
            .filter(c -> isUserCommit(c, gitlabIds, user, aliasEmails))
            .toList();
        int commitsInMrCount = userCommits.size();
        int filesChanged = userCommits.stream().mapToInt(MergeRequestCommit::getFilesChangedCount).sum();

        // --- Change volume (from commit stats — MR-level additions/deletions are not provided by this GitLab) ---
        int linesAdded = userCommits.stream().mapToInt(MergeRequestCommit::getAdditions).sum();
        int linesDeleted = userCommits.stream().mapToInt(MergeRequestCommit::getDeletions).sum();
        int linesChanged = linesAdded + linesDeleted;

        // MR size = total lines across all commits in each authored MR (not just user's commits)
        List<Integer> mrSizesLines = authoredMrIds.stream()
            .map(mrId -> commitsByMrId.getOrDefault(mrId, List.of()).stream()
                .mapToInt(c -> c.getAdditions() + c.getDeletions()).sum())
            .filter(size -> size > 0)
            .sorted()
            .collect(Collectors.toList());
        double avgMrSizeLines = mrSizesLines.isEmpty()
            ? 0
            : mean(mrSizesLines);
        double medianMrSizeLines = mrSizesLines.isEmpty()
            ? 0
            : median(mrSizesLines);

        List<Integer> mrSizesFiles = authoredMrs.stream()
            .map(MergeRequest::getFilesChangedCount)
            .sorted()
            .collect(Collectors.toList());
        double avgMrSizeFiles = mrSizesFiles.isEmpty()
            ? 0
            : mean(mrSizesFiles);

        // Active days: unique calendar days with any event (commit authored, note created, approval)
        Set<String> activeDays = new HashSet<>();
        userCommits.forEach(c -> {
            if (c.getAuthoredDate() != null) {
                activeDays.add(toDateString(c.getAuthoredDate()));
            }
        });

        // Notes authored by user (all MRs, not just authored ones)
        List<MergeRequestNote> userNotes = notesByMrId.values().stream()
            .flatMap(Collection::stream)
            .filter(n -> gitlabIds.contains(n.getAuthorGitlabUserId()))
            .toList();
        userNotes.forEach(n -> {
            if (n.getCreatedAtGitlab() != null) {
                activeDays.add(toDateString(n.getCreatedAtGitlab()));
            }
        });

        // Approvals given by user
        List<MergeRequestApproval> userApprovals = approvalsByMrId.values().stream()
            .flatMap(Collection::stream)
            .filter(a -> gitlabIds.contains(a.getApprovedByGitlabUserId()))
            .toList();
        userApprovals.forEach(a -> {
            if (a.getApprovedAtGitlab() != null) {
                activeDays.add(toDateString(a.getApprovedAtGitlab()));
            }
        });

        int activeDaysCount = activeDays.size();

        // --- Review contribution ---
        // Notes written in OTHER people's MRs (non-system only)
        List<MergeRequestNote> reviewNotesWritten = userNotes.stream()
            .filter(n -> !n.isSystem() && !authoredMrIds.contains(n.getMergeRequestId()))
            .toList();
        int reviewCommentsWrittenCount = reviewNotesWritten.size();

        // Review threads started = first note of a discussion in a foreign MR
        long reviewThreadsStarted = countReviewThreadsStarted(reviewNotesWritten, notesByMrId);

        // MRs reviewed = unique foreign MRs where user left at least one non-system note OR approved
        Set<Long> reviewedByNote = reviewNotesWritten.stream()
            .map(MergeRequestNote::getMergeRequestId)
            .collect(Collectors.toSet());
        Set<Long> approvedMrIds = userApprovals.stream()
            .map(MergeRequestApproval::getMergeRequestId)
            .collect(Collectors.toSet());
        // Only count foreign MRs as reviewed
        Set<Long> foreignApprovedMrIds = approvedMrIds.stream()
            .filter(id -> !authoredMrIds.contains(id))
            .collect(Collectors.toSet());

        Set<Long> reviewedMrIds = new HashSet<>(reviewedByNote);
        reviewedMrIds.addAll(foreignApprovedMrIds);
        int mrsReviewedCount = reviewedMrIds.size();

        int approvalsGivenCount = (int) userApprovals.stream()
            .filter(a -> !authoredMrIds.contains(a.getMergeRequestId()))
            .count();

        // --- Flow metrics on authored MRs ---
        List<Long> timeToFirstReview = new ArrayList<>();
        List<Long> timeToMerge = new ArrayList<>();
        int reworkMrCount = 0;
        int selfMergeCount = 0;

        for (MergeRequest mr : authoredMrs) {
            List<MergeRequestNote> mrNotes = notesByMrId.getOrDefault(mr.getId(), List.of());
            List<MergeRequestApproval> mrApprovals = approvalsByMrId.getOrDefault(mr.getId(), List.of());
            List<MergeRequestCommit> mrCommits = commitsByMrId.getOrDefault(mr.getId(), List.of());

            // First external review event timestamp
            Optional<Instant> firstExternalReview = findFirstExternalReviewEvent(
                mr, gitlabIds, mrNotes, mrApprovals);

            firstExternalReview.ifPresent(reviewAt -> {
                long minutes = ChronoUnit.MINUTES.between(mr.getCreatedAtGitlab(), reviewAt);
                if (minutes >= 0) {
                    timeToFirstReview.add(minutes);
                }
            });

            // Time to merge
            if (mr.getMergedAtGitlab() != null) {
                long minutes = ChronoUnit.MINUTES.between(mr.getCreatedAtGitlab(), mr.getMergedAtGitlab());
                if (minutes >= 0) {
                    timeToMerge.add(minutes);
                }
            }

            // Rework: author pushed a commit AFTER first external review
            if (firstExternalReview.isPresent()) {
                boolean hasRework = mrCommits.stream()
                    .filter(c -> isUserCommit(c, gitlabIds, user, aliasEmails))
                    .anyMatch(c -> c.getAuthoredDate() != null
                        && c.getAuthoredDate().isAfter(firstExternalReview.get()));
                if (hasRework) {
                    reworkMrCount++;
                }
            }

            // Self-merge
            if (mr.getMergedByGitlabUserId() != null && gitlabIds.contains(mr.getMergedByGitlabUserId())) {
                selfMergeCount++;
            }
        }

        double reworkRatio = mrMergedCount > 0
            ? (double) reworkMrCount / mrMergedCount
            : 0.0;
        double selfMergeRatio = mrMergedCount > 0
            ? (double) selfMergeCount / mrMergedCount
            : 0.0;

        // --- Normalized ---
        double mrMergedPerActiveDay = activeDaysCount > 0
            ? (double) mrMergedCount / activeDaysCount
            : 0.0;
        double commentsPerReviewedMr = mrsReviewedCount > 0
            ? (double) reviewCommentsWrittenCount / mrsReviewedCount
            : 0.0;

        return UserMetrics.builder()
            .trackedUserId(user.getId())
            .displayName(user.getDisplayName())
            // Delivery
            .mrOpenedCount(mrOpenedCount)
            .mrMergedCount(mrMergedCount)
            .activeDaysCount(activeDaysCount)
            .repositoriesTouchedCount(projectsTouched.size())
            .commitsInMrCount(commitsInMrCount)
            // Change volume
            .linesAdded(linesAdded)
            .linesDeleted(linesDeleted)
            .linesChanged(linesChanged)
            .filesChanged(filesChanged)
            .avgMrSizeLines(round2(avgMrSizeLines))
            .medianMrSizeLines(round2(medianMrSizeLines))
            .avgMrSizeFiles(round2(avgMrSizeFiles))
            // Review
            .reviewCommentsWrittenCount(reviewCommentsWrittenCount)
            .mrsReviewedCount(mrsReviewedCount)
            .approvalsGivenCount(approvalsGivenCount)
            .reviewThreadsStartedCount((int) reviewThreadsStarted)
            // Flow
            .avgTimeToFirstReviewMinutes(timeToFirstReview.isEmpty()
                ? null
                : round2(mean(timeToFirstReview)))
            .medianTimeToFirstReviewMinutes(timeToFirstReview.isEmpty()
                ? null
                : round2(median(timeToFirstReview)))
            .avgTimeToMergeMinutes(timeToMerge.isEmpty()
                ? null
                : round2(mean(timeToMerge)))
            .medianTimeToMergeMinutes(timeToMerge.isEmpty()
                ? null
                : round2(median(timeToMerge)))
            .reworkMrCount(reworkMrCount)
            .reworkRatio(round2(reworkRatio))
            .selfMergeCount(selfMergeCount)
            .selfMergeRatio(round2(selfMergeRatio))
            // Normalized
            .mrMergedPerActiveDay(round2(mrMergedPerActiveDay))
            .commentsPerReviewedMr(round2(commentsPerReviewedMr))
            .build();
    }

    @SuppressWarnings("PMD.UnusedFormalParameter")
    private Optional<Instant> findFirstExternalReviewEvent(MergeRequest mr,
                                                           Set<Long> authorGitlabIds,
                                                           List<MergeRequestNote> notes,
                                                           List<MergeRequestApproval> approvals) {
        // First non-system note by someone who is NOT the MR author
        Optional<Instant> firstNote = notes.stream()
            .filter(n -> !n.isSystem())
            .filter(n -> n.getAuthorGitlabUserId() != null
                && !authorGitlabIds.contains(n.getAuthorGitlabUserId()))
            .map(MergeRequestNote::getCreatedAtGitlab)
            .filter(Objects::nonNull)
            .min(Instant::compareTo);

        // First approval by someone who is NOT the MR author
        Optional<Instant> firstApproval = approvals.stream()
            .filter(a -> a.getApprovedByGitlabUserId() != null
                && !authorGitlabIds.contains(a.getApprovedByGitlabUserId()))
            .map(MergeRequestApproval::getApprovedAtGitlab)
            .filter(Objects::nonNull)
            .min(Instant::compareTo);

        if (firstNote.isEmpty()) {
            return firstApproval;
        }
        return firstApproval.map(instant -> firstNote.get().isBefore(instant)
                ? firstNote.get()
                : instant)
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
                (a, b) -> a.isBefore(b)
                    ? a
                    : b));

        return reviewNotes.stream()
            .filter(n -> n.getDiscussionId() != null && n.getCreatedAtGitlab() != null)
            .filter(n -> n.getCreatedAtGitlab().equals(firstNoteByDiscussion.get(n.getDiscussionId())))
            .count();
    }

    @SuppressWarnings("PMD.UnusedFormalParameter")
    private boolean isUserCommit(MergeRequestCommit commit,
                                 Set<Long> gitlabIds,
                                 TrackedUser user,
                                 Set<String> aliasEmails) {
        if (commit.getAuthorEmail() == null) {
            return false;
        }
        String email = commit.getAuthorEmail().toLowerCase(Locale.ROOT);
        return user.getEmail() != null && user.getEmail().equalsIgnoreCase(email)
            || aliasEmails.contains(email);
    }

    private Map<Long, Set<Long>> resolveGitlabIds(List<Long> userIds) {
        return aliasRepository.findByTrackedUserIdIn(userIds).stream()
            .filter(a -> a.getGitlabUserId() != null)
            .collect(Collectors.groupingBy(
                TrackedUserAlias::getTrackedUserId,
                Collectors.mapping(TrackedUserAlias::getGitlabUserId, Collectors.toSet())
            ));
    }

    private Map<Long, Set<String>> resolveAliasEmails(List<Long> userIds) {
        return aliasRepository.findByTrackedUserIdIn(userIds).stream()
            .filter(a -> a.getEmail() != null)
            .collect(Collectors.groupingBy(
                TrackedUserAlias::getTrackedUserId,
                Collectors.mapping(a -> a.getEmail().toLowerCase(Locale.ROOT), Collectors.toSet())
            ));
    }

    // -----------------------------------------------------------------------
    // Math helpers
    // -----------------------------------------------------------------------

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
        } else {
            return (sorted.get(size / 2 - 1).doubleValue() + sorted.get(size / 2).doubleValue()) / 2.0;
        }
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private String toDateString(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC).toLocalDate().toString();
    }
}
