package io.simakov.analytics.web;

import io.simakov.analytics.domain.model.MergeRequest;
import io.simakov.analytics.domain.model.MergeRequestApproval;
import io.simakov.analytics.domain.model.MergeRequestNote;
import io.simakov.analytics.domain.model.TrackedUser;
import io.simakov.analytics.domain.model.TrackedUserAlias;
import io.simakov.analytics.domain.model.enums.MrState;
import io.simakov.analytics.domain.repository.MergeRequestApprovalRepository;
import io.simakov.analytics.domain.repository.MergeRequestNoteRepository;
import io.simakov.analytics.domain.repository.MergeRequestRepository;
import io.simakov.analytics.domain.repository.TrackedUserAliasRepository;
import io.simakov.analytics.domain.repository.TrackedUserRepository;
import io.simakov.analytics.security.WorkspaceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@SuppressWarnings("PMD.GodClass")
@Service
@RequiredArgsConstructor
public class FlowService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter
        .ofPattern("dd.MM", Locale.ROOT)
        .withZone(ZoneOffset.UTC);

    private final MergeRequestRepository mrRepository;
    private final MergeRequestNoteRepository noteRepository;
    private final MergeRequestApprovalRepository approvalRepository;
    private final TrackedUserRepository trackedUserRepository;
    private final TrackedUserAliasRepository aliasRepository;

    /**
     * Flow stage breakdown: Review Wait, Review, Merge Wait.
     */
    @Transactional(readOnly = true)
    public FlowStagesResult buildFlowStages(List<Long> projectIds,
                                             Instant dateFrom,
                                             Instant dateTo) {
        List<MergeRequest> mergedMrs = mrRepository.findMergedInPeriod(projectIds, dateFrom, dateTo);
        if (mergedMrs.isEmpty()) {
            return new FlowStagesResult(List.of(), 0);
        }

        List<Long> mrIds = mergedMrs.stream().map(MergeRequest::getId).toList();
        Map<Long, List<MergeRequestNote>> notesByMr = noteRepository.findByMergeRequestIdIn(mrIds)
            .stream().collect(Collectors.groupingBy(MergeRequestNote::getMergeRequestId));
        Map<Long, List<MergeRequestApproval>> approvalsByMr = approvalRepository.findByMergeRequestIdIn(mrIds)
            .stream().collect(Collectors.groupingBy(MergeRequestApproval::getMergeRequestId));

        StageAccumulator acc = new StageAccumulator();
        for (MergeRequest mr : mergedMrs) {
            if (accumulateStages(mr, notesByMr, approvalsByMr, acc)) {
                acc.counted++;
            }
        }

        if (acc.counted == 0) {
            return new FlowStagesResult(List.of(), 0);
        }

        List<Map<String, Object>> stages = List.of(
            buildStageMap("Review Wait", acc.reviewWait, acc.counted, "warn",
                "От создания MR до первого ревью"),
            buildStageMap("Review", acc.review, acc.counted, "accent",
                "От первого до последнего ревью"),
            buildStageMap("Merge Wait", acc.mergeWait, acc.counted, "info",
                "От последнего ревью до мержа")
        );
        return new FlowStagesResult(stages, acc.counted);
    }

    private Map<String, Object> buildStageMap(String stage, long totalMinutes,
                                               int counted, String color,
                                               String description) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("stage", stage);
        map.put("hours", round1((double) totalMinutes / counted / 60.0));
        map.put("color", color);
        map.put("description", description);
        return map;
    }

    private boolean accumulateStages(MergeRequest mr,
                                     Map<Long, List<MergeRequestNote>> notesByMr,
                                     Map<Long, List<MergeRequestApproval>> approvalsByMr,
                                     StageAccumulator acc) {
        if (mr.getMergedAtGitlab() == null) {
            return false;
        }
        Instant created = mr.getCreatedAtGitlab();
        Instant merged = mr.getMergedAtGitlab();
        long totalMin = Duration.between(created, merged).toMinutes();
        if (totalMin <= 0) {
            return false;
        }

        List<MergeRequestNote> notes = notesByMr.getOrDefault(mr.getId(), List.of());
        List<MergeRequestApproval> approvals = approvalsByMr.getOrDefault(mr.getId(), List.of());
        Instant firstReview = findFirstExternalEvent(mr.getAuthorGitlabUserId(), notes, approvals);

        if (firstReview == null || firstReview.isAfter(merged)) {
            acc.reviewWait += totalMin;
            return true;
        }

        Instant lastReview = findLastExternalEvent(mr.getAuthorGitlabUserId(), notes, approvals);
        acc.reviewWait += Math.max(0, Duration.between(created, firstReview).toMinutes());
        if (lastReview != null && !lastReview.isAfter(merged)) {
            acc.review += Math.max(0, Duration.between(firstReview, lastReview).toMinutes());
            acc.mergeWait += Math.max(0, Duration.between(lastReview, merged).toMinutes());
        } else {
            acc.mergeWait += Math.max(0, Duration.between(firstReview, merged).toMinutes());
        }
        return true;
    }

    /**
     * Stuck (open) MRs older than minHours.
     */
    @Transactional(readOnly = true)
    public List<StuckMrRow> buildStuckMrs(List<Long> projectIds, int minHours) {
        List<MergeRequest> openMrs = mrRepository.findOpenByProjectIds(projectIds, MrState.OPENED);
        Instant now = Instant.now();

        return openMrs.stream()
            .filter(mr -> Duration.between(mr.getCreatedAtGitlab(), now).toHours() >= minHours)
            .sorted(Comparator.comparing(MergeRequest::getCreatedAtGitlab))
            .map(mr -> toStuckMrRow(mr, now))
            .toList();
    }

    private StuckMrRow toStuckMrRow(MergeRequest mr, Instant now) {
        long daysOpen = Duration.between(mr.getCreatedAtGitlab(), now).toDays();
        String severity = daysOpen > 30 ? "bad" : daysOpen > 10 ? "warn" : "info";
        int additions = mr.getNetAdditions() != null ? mr.getNetAdditions() : mr.getAdditions();
        int deletions = mr.getNetDeletions() != null ? mr.getNetDeletions() : mr.getDeletions();
        return new StuckMrRow(
            mr.getGitlabMrIid(), mr.getTitle(), mr.getAuthorName(),
            DATE_FMT.format(mr.getCreatedAtGitlab()), daysOpen,
            additions, deletions, severity, mr.getWebUrl()
        );
    }

    /**
     * Review balance: how many reviews each tracked user did in the period.
     */
    @Transactional(readOnly = true)
    public List<ReviewBalanceRow> buildReviewBalance(List<Long> projectIds,
                                                     Instant dateFrom,
                                                     Instant dateTo) {
        Long workspaceId = WorkspaceContext.get();
        List<TrackedUser> users = trackedUserRepository.findAllByWorkspaceId(workspaceId);
        Map<Long, Set<Long>> gitlabIdsByUser = resolveGitlabIdsByUser(users);

        List<MergeRequest> mergedMrs = mrRepository.findMergedInPeriod(projectIds, dateFrom, dateTo);
        Map<Long, Integer> reviewCountByGitlabId = countReviewsByGitlabId(mergedMrs);

        List<ReviewBalanceRow> rows = new ArrayList<>();
        for (TrackedUser user : users) {
            Set<Long> gids = gitlabIdsByUser.getOrDefault(user.getId(), Set.of());
            int reviews = gids.stream()
                .mapToInt(gid -> reviewCountByGitlabId.getOrDefault(gid, 0))
                .sum();
            if (reviews > 0) {
                rows.add(new ReviewBalanceRow(user.getId(), user.getDisplayName(), reviews));
            }
        }
        rows.sort(Comparator.comparingInt(ReviewBalanceRow::reviews).reversed());
        return rows;
    }

    private Map<Long, Integer> countReviewsByGitlabId(List<MergeRequest> mergedMrs) {
        List<Long> mrIds = mergedMrs.stream().map(MergeRequest::getId).toList();
        Map<Long, List<MergeRequestNote>> notesByMr = loadNotesByMr(mrIds);
        Map<Long, List<MergeRequestApproval>> approvalsByMr = loadApprovalsByMr(mrIds);

        Map<Long, Integer> counts = new HashMap<>();
        for (MergeRequest mr : mergedMrs) {
            Set<Long> authorIds = mr.getAuthorGitlabUserId() != null
                ? Set.of(mr.getAuthorGitlabUserId()) : Set.of();
            countExternalReviewers(notesByMr, approvalsByMr, mr.getId(), authorIds, counts);
        }
        return counts;
    }

    private void countExternalReviewers(Map<Long, List<MergeRequestNote>> notesByMr,
                                        Map<Long, List<MergeRequestApproval>> approvalsByMr,
                                        Long mrId, Set<Long> authorIds,
                                        Map<Long, Integer> counts) {
        notesByMr.getOrDefault(mrId, List.of()).stream()
            .filter(n -> !n.isSystem())
            .filter(n -> n.getAuthorGitlabUserId() != null)
            .filter(n -> !authorIds.contains(n.getAuthorGitlabUserId()))
            .map(MergeRequestNote::getAuthorGitlabUserId)
            .distinct()
            .forEach(gid -> counts.merge(gid, 1, Integer::sum));

        approvalsByMr.getOrDefault(mrId, List.of()).stream()
            .filter(a -> a.getApprovedByGitlabUserId() != null)
            .filter(a -> !authorIds.contains(a.getApprovedByGitlabUserId()))
            .map(MergeRequestApproval::getApprovedByGitlabUserId)
            .distinct()
            .forEach(gid -> counts.merge(gid, 1, Integer::sum));
    }

    /**
     * Review matrix: reviewer x author grid.
     * Returns map with "devNames" and "matrix" keys.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> buildReviewMatrix(List<Long> projectIds,
                                                  Instant dateFrom,
                                                  Instant dateTo) {
        Long workspaceId = WorkspaceContext.get();
        List<TrackedUser> users = trackedUserRepository.findAllByWorkspaceId(workspaceId);
        Map<Long, Long> gitlabIdToTrackedUserId = resolveGitlabToTrackedMap(users);

        List<MergeRequest> mergedMrs = mrRepository.findMergedInPeriod(projectIds, dateFrom, dateTo);
        Map<Long, Map<Long, Integer>> matrix = buildMatrixData(
            mergedMrs, gitlabIdToTrackedUserId);
        return formatMatrix(users, matrix);
    }

    private Map<Long, Map<Long, Integer>> buildMatrixData(
        List<MergeRequest> mergedMrs,
        Map<Long, Long> gitlabIdToTrackedUserId) {

        List<Long> mrIds = mergedMrs.stream().map(MergeRequest::getId).toList();
        Map<Long, List<MergeRequestNote>> notesByMr = loadNotesByMr(mrIds);

        Map<Long, Map<Long, Integer>> matrix = new HashMap<>();
        for (MergeRequest mr : mergedMrs) {
            populateMatrixRow(mr, notesByMr, gitlabIdToTrackedUserId, matrix);
        }
        return matrix;
    }

    private void populateMatrixRow(MergeRequest mr,
                                   Map<Long, List<MergeRequestNote>> notesByMr,
                                   Map<Long, Long> gitlabIdToTrackedUserId,
                                   Map<Long, Map<Long, Integer>> matrix) {
        Long authorGitlabId = mr.getAuthorGitlabUserId();
        Long authorTrackedId = authorGitlabId != null
            ? gitlabIdToTrackedUserId.get(authorGitlabId) : null;
        if (authorTrackedId == null) {
            return;
        }

        notesByMr.getOrDefault(mr.getId(), List.of()).stream()
            .filter(n -> !n.isSystem())
            .filter(n -> n.getAuthorGitlabUserId() != null)
            .filter(n -> !n.getAuthorGitlabUserId().equals(authorGitlabId))
            .map(MergeRequestNote::getAuthorGitlabUserId)
            .collect(Collectors.toSet())
            .forEach(reviewerGitlabId -> {
                Long reviewerTrackedId = gitlabIdToTrackedUserId.get(reviewerGitlabId);
                if (reviewerTrackedId != null) {
                    matrix.computeIfAbsent(reviewerTrackedId, k -> new HashMap<>())
                        .merge(authorTrackedId, 1, Integer::sum);
                }
            });
    }

    private Map<String, Object> formatMatrix(List<TrackedUser> users,
                                              Map<Long, Map<Long, Integer>> matrix) {
        Set<Long> activeUserIds = Stream.concat(
            matrix.keySet().stream(),
            matrix.values().stream().flatMap(m -> m.keySet().stream())
        ).collect(Collectors.toSet());

        List<TrackedUser> activeUsers = users.stream()
            .filter(u -> activeUserIds.contains(u.getId()))
            .sorted(Comparator.comparing(TrackedUser::getDisplayName))
            .toList();

        List<String> devNames = activeUsers.stream()
            .map(u -> firstName(u.getDisplayName())).toList();
        List<Long> orderedIds = activeUsers.stream().map(TrackedUser::getId).toList();

        int size = orderedIds.size();
        int[][] grid = new int[size][size];
        for (int r = 0; r < size; r++) {
            Map<Long, Integer> row = matrix.getOrDefault(orderedIds.get(r), Map.of());
            for (int c = 0; c < size; c++) {
                grid[r][c] = row.getOrDefault(orderedIds.get(c), 0);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("devNames", devNames);
        result.put("devIds", orderedIds);
        result.put("matrix", grid);
        return result;
    }

    // ── Data loading helpers ──

    private Map<Long, List<MergeRequestNote>> loadNotesByMr(List<Long> mrIds) {
        return mrIds.isEmpty() ? Map.of()
            : noteRepository.findByMergeRequestIdIn(mrIds).stream()
                .collect(Collectors.groupingBy(MergeRequestNote::getMergeRequestId));
    }

    private Map<Long, List<MergeRequestApproval>> loadApprovalsByMr(List<Long> mrIds) {
        return mrIds.isEmpty() ? Map.of()
            : approvalRepository.findByMergeRequestIdIn(mrIds).stream()
                .collect(Collectors.groupingBy(MergeRequestApproval::getMergeRequestId));
    }

    private Map<Long, Set<Long>> resolveGitlabIdsByUser(List<TrackedUser> users) {
        List<TrackedUserAlias> aliases = aliasRepository.findByTrackedUserIdIn(
            users.stream().map(TrackedUser::getId).toList());
        return aliases.stream()
            .filter(a -> a.getGitlabUserId() != null)
            .collect(Collectors.groupingBy(
                TrackedUserAlias::getTrackedUserId,
                Collectors.mapping(TrackedUserAlias::getGitlabUserId, Collectors.toSet())));
    }

    private Map<Long, Long> resolveGitlabToTrackedMap(List<TrackedUser> users) {
        List<TrackedUserAlias> aliases = aliasRepository.findByTrackedUserIdIn(
            users.stream().map(TrackedUser::getId).toList());
        Map<Long, Long> map = new HashMap<>();
        for (TrackedUserAlias alias : aliases) {
            if (alias.getGitlabUserId() != null) {
                map.put(alias.getGitlabUserId(), alias.getTrackedUserId());
            }
        }
        return map;
    }

    // ── Event helpers ──

    private Instant findFirstExternalEvent(Long authorGitlabUserId,
                                           List<MergeRequestNote> notes,
                                           List<MergeRequestApproval> approvals) {
        return findExternalEvent(authorGitlabUserId, notes, approvals, true);
    }

    private Instant findLastExternalEvent(Long authorGitlabUserId,
                                          List<MergeRequestNote> notes,
                                          List<MergeRequestApproval> approvals) {
        return findExternalEvent(authorGitlabUserId, notes, approvals, false);
    }

    private Instant findExternalEvent(Long authorGitlabUserId,
                                      List<MergeRequestNote> notes,
                                      List<MergeRequestApproval> approvals,
                                      boolean findFirst) {
        Stream<Instant> noteStream = notes.stream()
            .filter(n -> !n.isSystem())
            .filter(n -> n.getAuthorGitlabUserId() != null
                && !n.getAuthorGitlabUserId().equals(authorGitlabUserId))
            .map(MergeRequestNote::getCreatedAtGitlab)
            .filter(Objects::nonNull);
        Instant noteTs = findFirst
            ? noteStream.min(Instant::compareTo).orElse(null)
            : noteStream.max(Instant::compareTo).orElse(null);

        Stream<Instant> approvalStream = approvals.stream()
            .filter(a -> a.getApprovedByGitlabUserId() != null
                && !a.getApprovedByGitlabUserId().equals(authorGitlabUserId))
            .map(MergeRequestApproval::getApprovedAtGitlab)
            .filter(Objects::nonNull);
        Instant approvalTs = findFirst
            ? approvalStream.min(Instant::compareTo).orElse(null)
            : approvalStream.max(Instant::compareTo).orElse(null);

        if (noteTs == null) {
            return approvalTs;
        }
        if (approvalTs == null) {
            return noteTs;
        }
        if (findFirst) {
            return noteTs.isBefore(approvalTs) ? noteTs : approvalTs;
        }
        return noteTs.isAfter(approvalTs) ? noteTs : approvalTs;
    }

    private static String firstName(String displayName) {
        if (displayName == null) {
            return "?";
        }
        int space = displayName.indexOf(' ');
        return space > 0 ? displayName.substring(0, space) : displayName;
    }

    private static double round1(double val) {
        return Math.round(val * 10.0) / 10.0;
    }

    // ── Accumulator ──

    private static class StageAccumulator {
        long reviewWait;
        long review;
        long mergeWait;
        int counted;
    }

    // ── DTOs ──

    public record FlowStagesResult(
        List<Map<String, Object>> stages,
        int mrCount
    ) {
    }

    public record StuckMrRow(
        Long iid,
        String title,
        String authorName,
        String opened,
        long daysOpen,
        int additions,
        int deletions,
        String severity,
        String webUrl
    ) {
    }

    public record ReviewBalanceRow(
        Long userId,
        String displayName,
        int reviews
    ) {
    }
}
