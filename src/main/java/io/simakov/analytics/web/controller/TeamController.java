package io.simakov.analytics.web.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.simakov.analytics.domain.model.MergeRequest;
import io.simakov.analytics.domain.model.MergeRequestCommit;
import io.simakov.analytics.domain.model.SyncJob;
import io.simakov.analytics.domain.model.TrackedProject;
import io.simakov.analytics.domain.model.TrackedUser;
import io.simakov.analytics.domain.model.TrackedUserAlias;
import io.simakov.analytics.domain.model.enums.MrState;
import io.simakov.analytics.domain.model.enums.PeriodType;
import io.simakov.analytics.domain.repository.MergeRequestCommitRepository;
import io.simakov.analytics.domain.repository.MergeRequestRepository;
import io.simakov.analytics.domain.repository.TrackedUserAliasRepository;
import io.simakov.analytics.domain.repository.TrackedUserRepository;
import io.simakov.analytics.metrics.MetricCalculationService;
import io.simakov.analytics.metrics.model.UserMetrics;
import io.simakov.analytics.security.WorkspaceContext;
import io.simakov.analytics.sync.SyncJobService;
import io.simakov.analytics.web.SettingsViewService;
import io.simakov.analytics.web.dto.DevProfileMrRow;
import io.simakov.analytics.web.dto.SettingsPageData;
import io.simakov.analytics.workspace.WorkspaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Controller
@RequiredArgsConstructor
public class TeamController {

    private static final int SLOW_MEDIAN_HOURS = 12;
    private static final DateTimeFormatter MR_DATE_FMT =
        DateTimeFormatter.ofPattern("d MMM", new Locale("ru"));

    private final MetricCalculationService metricCalculationService;
    private final MergeRequestRepository mrRepository;
    private final MergeRequestCommitRepository commitRepository;
    private final TrackedUserRepository trackedUserRepository;
    private final TrackedUserAliasRepository aliasRepository;
    private final SettingsViewService settingsViewService;
    private final WorkspaceService workspaceService;
    private final SyncJobService syncJobService;
    private final ObjectMapper objectMapper;

    @GetMapping("/team")
    @SuppressWarnings("checkstyle:IllegalCatch")
    public String team(@RequestParam(defaultValue = "LAST_30_DAYS") String period,
                       @RequestParam(required = false) List<Long> projectIds,
                       Model model) {
        Long workspaceId = WorkspaceContext.get();

        PeriodType periodType = parsePeriod(period);
        int days = periodType.toDays();
        Instant dateTo = Instant.now();
        Instant dateFrom = dateTo.minus(days, ChronoUnit.DAYS);

        SettingsPageData sidebarData = settingsViewService.buildSettingsPage();
        List<TrackedProject> allProjects = sidebarData.projects();
        List<Long> effectiveProjectIds = resolveProjectIds(projectIds, allProjects);

        // Sidebar attributes
        populateSidebar(model, sidebarData, workspaceId);

        // Page filters
        model.addAttribute("selectedProjectIds", effectiveProjectIds);
        model.addAttribute("selectedPeriod", periodType.name());

        // Team data
        List<TrackedUser> users = trackedUserRepository.findAllByWorkspaceId(workspaceId)
            .stream().filter(TrackedUser::isEnabled).toList();
        List<Long> userIds = users.stream().map(TrackedUser::getId).toList();

        Map<Long, UserMetrics> metricsMap = metricCalculationService.calculate(
            effectiveProjectIds, userIds, dateFrom, dateTo);

        List<UserMetrics> cards = new ArrayList<>(metricsMap.values().stream()
            .filter(m -> !m.isInactive())
            .sorted(Comparator.comparingInt(UserMetrics::getMrMergedCount).reversed())
            .toList());
        model.addAttribute("cards", cards);

        // Compute team averages for badge thresholds
        double avgMrs = cards.isEmpty() ? 0
            : cards.stream().mapToInt(UserMetrics::getMrMergedCount).average().orElse(0);
        model.addAttribute("avgMrs", avgMrs);

        // Counts for filter tabs
        long issueCount = cards.stream()
            .filter(c -> medianHours(c) > SLOW_MEDIAN_HOURS)
            .count();
        long starCount = cards.stream()
            .filter(c -> c.getMrMergedCount() >= avgMrs * 1.5 && medianHours(c) <= SLOW_MEDIAN_HOURS)
            .count();
        model.addAttribute("totalCount", cards.size());
        model.addAttribute("issueCount", issueCount);
        model.addAttribute("starCount", starCount);

        // Sparkline data: weekly MR counts per trackedUserId
        Map<Long, List<Integer>> sparklines = buildSparklines(
            effectiveProjectIds, dateFrom, dateTo, days, users);
        try {
            model.addAttribute("sparklinesJson", objectMapper.writeValueAsString(sparklines));
        } catch (JsonProcessingException e) {
            model.addAttribute("sparklinesJson", "{}");
        }

        return "team";
    }

    @GetMapping("/profile/{userId}")
    @SuppressWarnings("checkstyle:IllegalCatch")
    public String devProfile(@PathVariable Long userId,
                             @RequestParam(defaultValue = "LAST_30_DAYS") String period,
                             @RequestParam(required = false) List<Long> projectIds,
                             Model model) {
        Long workspaceId = WorkspaceContext.get();

        PeriodType periodType = parsePeriod(period);
        int days = periodType.toDays();
        Instant dateTo = Instant.now();
        Instant dateFrom = dateTo.minus(days, ChronoUnit.DAYS);

        SettingsPageData sidebarData = settingsViewService.buildSettingsPage();
        List<Long> effectiveProjectIds = resolveProjectIds(projectIds, sidebarData.projects());

        // Sidebar attributes
        populateSidebar(model, sidebarData, workspaceId);

        model.addAttribute("selectedProjectIds", effectiveProjectIds);
        model.addAttribute("selectedPeriod", periodType.name());

        // User data
        TrackedUser user = trackedUserRepository.findById(userId).orElse(null);
        if (user == null || !user.getWorkspaceId().equals(workspaceId)) {
            return "redirect:/team";
        }
        model.addAttribute("dev", user);

        populateDevMetrics(model, effectiveProjectIds, userId, dateFrom, dateTo);

        // GitLab username from aliases
        List<TrackedUserAlias> aliases = aliasRepository.findByTrackedUserIdIn(List.of(userId));
        Set<Long> gitlabIds = aliases.stream()
            .map(TrackedUserAlias::getGitlabUserId).filter(Objects::nonNull).collect(Collectors.toSet());
        String gitlabUsername = aliases.stream()
            .map(TrackedUserAlias::getUsername).filter(Objects::nonNull).findFirst().orElse(null);
        model.addAttribute("gitlabUsername", gitlabUsername);

        // MR list with pre-computed columns
        List<MergeRequest> userMrs = fetchUserMrs(effectiveProjectIds, dateFrom, dateTo, gitlabIds);
        List<Long> mrIds = userMrs.stream().map(MergeRequest::getId).toList();
        List<MergeRequestCommit> commits = mrIds.isEmpty()
            ? List.of() : commitRepository.findByMergeRequestIdIn(mrIds);
        Map<Long, int[]> commitStatsByMr = buildCommitStatsByMr(commits);
        model.addAttribute("mrRows", buildMrRows(userMrs, commitStatsByMr));
        model.addAttribute("stuckMrCount", countOpenMrs(effectiveProjectIds, gitlabIds));
        model.addAttribute("periodWeeks", Math.max(1, days / 7));

        // Sparkline + month labels
        Map<Long, List<Integer>> sparklines = buildSparklines(
            effectiveProjectIds, dateFrom, dateTo, days, List.of(user));
        try {
            List<Integer> sparkData = sparklines.getOrDefault(userId, List.of());
            model.addAttribute("sparkJson", objectMapper.writeValueAsString(sparkData));
            model.addAttribute("sparkLabels", objectMapper.writeValueAsString(buildSparkLabels(dateFrom, days)));
            model.addAttribute("calendarFrom", dateFrom.atZone(ZoneOffset.UTC).toLocalDate().toString());
            model.addAttribute("calendarJson", objectMapper.writeValueAsString(buildCalendarCounts(commits)));
        } catch (JsonProcessingException e) {
            model.addAttribute("sparkJson", "[]");
            model.addAttribute("sparkLabels", "[]");
            model.addAttribute("calendarFrom", dateFrom.atZone(ZoneOffset.UTC).toLocalDate().toString());
            model.addAttribute("calendarJson", "{}");
        }

        return "dev-profile";
    }

    private static PeriodType parsePeriod(String period) {
        try {
            return PeriodType.valueOf(period);
        } catch (IllegalArgumentException e) {
            return PeriodType.LAST_30_DAYS;
        }
    }

    private static List<Long> resolveProjectIds(List<Long> requestedIds,
                                                 List<TrackedProject> allProjects) {
        Set<Long> workspaceProjectIds = allProjects.stream()
            .map(TrackedProject::getId).collect(Collectors.toSet());
        if (requestedIds != null && !requestedIds.isEmpty()) {
            return requestedIds.stream().filter(workspaceProjectIds::contains).toList();
        }
        return allProjects.stream().map(TrackedProject::getId).toList();
    }

    private void populateDevMetrics(Model model, List<Long> projectIds,
                                     Long userId, Instant dateFrom, Instant dateTo) {
        Map<Long, UserMetrics> metricsMap = metricCalculationService.calculate(
            projectIds, List.of(userId), dateFrom, dateTo);
        UserMetrics metrics = metricsMap.get(userId);
        model.addAttribute("metrics", metrics);

        double medianH = metrics != null && metrics.getMedianTimeToMergeMinutes() != null
            ? metrics.getMedianTimeToMergeMinutes() / 60.0 : 0;
        String status = medianH > 24 ? "bad" : medianH > 12 ? "warn" : "good";
        model.addAttribute("medianH", Math.round(medianH * 10.0) / 10.0);
        model.addAttribute("devStatus", status);
    }

    private List<MergeRequest> fetchUserMrs(List<Long> projectIds,
                                             Instant dateFrom, Instant dateTo,
                                             Set<Long> gitlabIds) {
        if (gitlabIds.isEmpty()) {
            return List.of();
        }
        return mrRepository.findMergedInPeriod(projectIds, dateFrom, dateTo).stream()
            .filter(mr -> mr.getAuthorGitlabUserId() != null
                && gitlabIds.contains(mr.getAuthorGitlabUserId()))
            .sorted(Comparator.comparing(MergeRequest::getMergedAtGitlab,
                Comparator.nullsLast(Comparator.reverseOrder())))
            .toList();
    }

    private int countOpenMrs(List<Long> projectIds, Set<Long> gitlabIds) {
        if (gitlabIds.isEmpty()) {
            return 0;
        }
        return (int) mrRepository.findOpenByProjectIds(projectIds, MrState.OPENED).stream()
            .filter(mr -> mr.getAuthorGitlabUserId() != null
                && gitlabIds.contains(mr.getAuthorGitlabUserId()))
            .count();
    }

    private static Map<Long, int[]> buildCommitStatsByMr(List<MergeRequestCommit> commits) {
        Map<Long, int[]> map = new HashMap<>();
        for (MergeRequestCommit c : commits) {
            if (c.isMergeCommit()) {
                continue;
            }
            int[] stats = map.computeIfAbsent(c.getMergeRequestId(), k -> new int[2]);
            stats[0] += c.getAdditions();
            stats[1] += c.getDeletions();
        }
        return map;
    }

    private static List<DevProfileMrRow> buildMrRows(List<MergeRequest> mrs,
                                                       Map<Long, int[]> commitStats) {
        List<DevProfileMrRow> rows = new ArrayList<>(mrs.size());
        for (MergeRequest mr : mrs) {
            rows.add(toMrRow(mr, commitStats.get(mr.getId())));
        }
        return rows;
    }

    private static DevProfileMrRow toMrRow(MergeRequest mr, int[] stats) {
        String openedAt = formatMrDate(mr.getCreatedAtGitlab());
        String mergedAt = formatMrDate(mr.getMergedAtGitlab());
        String[] ttm = formatTimeToMerge(mr);
        int add = mr.getNetAdditions() != null ? mr.getNetAdditions() : stats != null ? stats[0] : 0;
        int del = mr.getNetDeletions() != null ? mr.getNetDeletions() : stats != null ? stats[1] : 0;
        String[] addDel = formatAddDel(add, del);
        String title = mr.getTitle() != null ? mr.getTitle() : "MR #" + mr.getGitlabMrIid();
        if (title.length() > 70) {
            title = title.substring(0, 67) + "...";
        }
        return new DevProfileMrRow(title, mr.getWebUrl(), openedAt, mergedAt,
            ttm[0], ttm[1], addDel[0], addDel[1]);
    }

    private static String formatMrDate(Instant instant) {
        if (instant == null) {
            return "—";
        }
        return instant.atZone(ZoneOffset.UTC).format(MR_DATE_FMT);
    }

    private static String[] formatAddDel(int additions, int deletions) {
        return new String[]{
            additions > 0 ? "+" + additions : "—",
            deletions > 0 ? "-" + deletions : "—"
        };
    }

    private static String[] formatTimeToMerge(MergeRequest mr) {
        if (mr.getMergedAtGitlab() == null || mr.getCreatedAtGitlab() == null) {
            return new String[]{"—", "var(--fg-2)"};
        }
        long seconds = mr.getMergedAtGitlab().getEpochSecond() - mr.getCreatedAtGitlab().getEpochSecond();
        if (seconds < 0) {
            seconds = 0;
        }
        double hours = seconds / 3600.0;
        String text = hours < 24
            ? String.format(Locale.US, "%.1fч", hours)
            : String.format(Locale.US, "%.1fд", hours / 24.0);
        String color = hours > 24 ? "var(--bad)" : hours > 12 ? "var(--warn)" : "var(--fg-1)";
        return new String[]{text, color};
    }

    private static List<String> buildSparkLabels(Instant dateFrom, int days) {
        int weeks = Math.max(1, days / 7);
        List<String> labels = new ArrayList<>(weeks);
        String lastMonth = null;
        for (int i = 0; i < weeks; i++) {
            ZonedDateTime wk = dateFrom.plus(i * 7L, ChronoUnit.DAYS).atZone(ZoneOffset.UTC);
            String month = wk.getMonth().getDisplayName(TextStyle.SHORT, new Locale("ru"));
            if (month.equals(lastMonth)) {
                labels.add("");
            } else {
                labels.add(month);
                lastMonth = month;
            }
        }
        return labels;
    }

    private static Map<String, Integer> buildCalendarCounts(List<MergeRequestCommit> commits) {
        Map<String, Integer> counts = new HashMap<>();
        for (MergeRequestCommit c : commits) {
            if (c.isMergeCommit() || c.getAuthoredDate() == null) {
                continue;
            }
            String date = c.getAuthoredDate().atZone(ZoneOffset.UTC).toLocalDate().toString();
            counts.merge(date, 1, Integer::sum);
        }
        return counts;
    }

    private void populateSidebar(Model model, SettingsPageData sidebarData,
                                  Long workspaceId) {
        model.addAttribute("allProjects", sidebarData.projects());
        model.addAttribute("sources", sidebarData.sources());
        model.addAttribute("usersWithAliases", sidebarData.usersWithAliases());
        model.addAttribute("activeJobIds", sidebarData.activeJobIds());
        model.addAttribute("hasProjects", sidebarData.hasProjects());
        model.addAttribute("showInactive", false);
        model.addAttribute("workspaceName", workspaceService.findWorkspaceName(workspaceId));
        Long enrichmentJobId = syncJobService.findActiveEnrichmentJob(workspaceId)
            .map(SyncJob::getId).orElse(null);
        model.addAttribute("enrichmentJobId", enrichmentJobId);
        model.addAttribute("releaseJobIds", syncJobService.findActiveReleaseJobIds(workspaceId));
        model.addAttribute("jiraJobIds", syncJobService.findActiveJiraJobIds(workspaceId));
    }

    private Map<Long, List<Integer>> buildSparklines(List<Long> projectIds,
                                                      Instant dateFrom,
                                                      Instant dateTo,
                                                      int days,
                                                      List<TrackedUser> users) {
        List<MergeRequest> mergedMrs = mrRepository.findMergedInPeriod(projectIds, dateFrom, dateTo);
        Map<Long, Long> gitlabToTrackedId = resolveGitlabToTrackedMap(users);
        int weeks = Math.max(1, days / 7);

        Map<Long, int[]> weeklyCountsMap = new HashMap<>();
        for (MergeRequest mr : mergedMrs) {
            Long trackedId = mr.getAuthorGitlabUserId() != null
                ? gitlabToTrackedId.get(mr.getAuthorGitlabUserId()) : null;
            if (trackedId == null || mr.getMergedAtGitlab() == null) {
                continue;
            }
            long daysAgo = ChronoUnit.DAYS.between(mr.getMergedAtGitlab(), dateTo);
            int weekIdx = Math.min((int) (daysAgo / 7), weeks - 1);
            weeklyCountsMap.computeIfAbsent(trackedId, k -> new int[weeks])[weeks - 1 - weekIdx]++;
        }

        Map<Long, List<Integer>> result = new HashMap<>();
        for (Map.Entry<Long, int[]> entry : weeklyCountsMap.entrySet()) {
            List<Integer> list = new ArrayList<>(entry.getValue().length);
            for (int v : entry.getValue()) {
                list.add(v);
            }
            result.put(entry.getKey(), list);
        }
        return result;
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

    private static double medianHours(UserMetrics m) {
        return m.getMedianTimeToMergeMinutes() != null
            ? m.getMedianTimeToMergeMinutes() / 60.0 : 0;
    }
}
