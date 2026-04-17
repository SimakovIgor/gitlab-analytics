package io.simakov.analytics.web.controller;

import io.simakov.analytics.domain.model.TrackedProject;
import io.simakov.analytics.domain.model.TrackedUser;
import io.simakov.analytics.domain.model.enums.PeriodType;
import io.simakov.analytics.domain.repository.TrackedProjectRepository;
import io.simakov.analytics.domain.repository.TrackedUserRepository;
import io.simakov.analytics.metrics.MetricCalculationService;
import io.simakov.analytics.metrics.model.UserMetrics;
import io.simakov.analytics.util.DateTimeUtils;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Controller
public class WebController {

    private final TrackedUserRepository trackedUserRepository;
    private final TrackedProjectRepository trackedProjectRepository;
    private final MetricCalculationService metricCalculationService;

    public WebController(TrackedUserRepository trackedUserRepository,
                         TrackedProjectRepository trackedProjectRepository,
                         MetricCalculationService metricCalculationService) {
        this.trackedUserRepository = trackedUserRepository;
        this.trackedProjectRepository = trackedProjectRepository;
        this.metricCalculationService = metricCalculationService;
    }

    @GetMapping("/")
    public String home() {
        return "redirect:/dashboard";
    }

    @GetMapping("/login")
    public String login() {
        return "login";
    }

    @GetMapping("/dashboard")
    public String dashboard(OAuth2AuthenticationToken authentication, Model model) {
        if (authentication != null) {
            model.addAttribute("currentUser", resolveUser(authentication));
        }

        List<TrackedUser> users = trackedUserRepository.findAll();
        List<TrackedProject> projects = trackedProjectRepository.findAll();

        model.addAttribute("users", users);
        model.addAttribute("projects", projects);
        model.addAttribute("usersTotal", users.size());
        model.addAttribute("usersActive", users.stream().filter(TrackedUser::isEnabled).count());
        model.addAttribute("projectsTotal", projects.size());
        model.addAttribute("projectsActive", projects.stream().filter(TrackedProject::isEnabled).count());
        model.addAttribute("needsOnboarding", projects.isEmpty() && users.isEmpty());

        return "dashboard";
    }

    @GetMapping("/report")
    public String report(OAuth2AuthenticationToken authentication,
                         @RequestParam(defaultValue = "LAST_30_DAYS") String period,
                         @RequestParam(required = false) List<Long> projectIds,
                         @RequestParam(defaultValue = "false") boolean showInactive,
                         Model model) {
        if (authentication != null) {
            model.addAttribute("currentUser", resolveUser(authentication));
        }

        List<TrackedProject> allProjects = trackedProjectRepository.findAll();
        List<TrackedUser> allUsers = trackedUserRepository.findAll();

        List<Long> resolvedProjectIds = (projectIds == null || projectIds.isEmpty())
            ? allProjects.stream().map(TrackedProject::getId).toList()
            : projectIds;

        List<TrackedUser> filteredUsers = showInactive
            ? allUsers
            : allUsers.stream().filter(TrackedUser::isEnabled).toList();

        List<Long> userIds = filteredUsers.stream().map(TrackedUser::getId).toList();

        int days = periodToDays(period);
        Instant dateTo = DateTimeUtils.now();
        Instant dateFrom = DateTimeUtils.minusDays(dateTo, days);
        Instant prevDateTo = dateFrom;
        Instant prevDateFrom = DateTimeUtils.minusDays(prevDateTo, days);

        List<UserMetrics> metrics = List.of();
        Map<Long, Map<String, Number>> deltas = Map.of();

        if (!userIds.isEmpty() && !resolvedProjectIds.isEmpty()) {
            Map<Long, UserMetrics> byUser = metricCalculationService.calculate(
                resolvedProjectIds, userIds, dateFrom, dateTo);
            Map<Long, UserMetrics> prevByUser = metricCalculationService.calculate(
                resolvedProjectIds, userIds, prevDateFrom, prevDateTo);

            metrics = allUsers.stream()
                .map(u -> byUser.get(u.getId()))
                .filter(m -> m != null)
                .sorted(Comparator.comparingInt(UserMetrics::getMrMergedCount).reversed())
                .toList();

            deltas = buildDeltas(metrics, prevByUser);
        }

        model.addAttribute("allProjects", allProjects);
        model.addAttribute("selectedProjectIds", resolvedProjectIds);
        model.addAttribute("selectedPeriod", period);
        model.addAttribute("showInactive", showInactive);
        model.addAttribute("dateFrom", dateFrom);
        model.addAttribute("dateTo", dateTo);
        model.addAttribute("metrics", metrics);
        model.addAttribute("deltas", deltas);

        return "report";
    }

    /**
     * Вычисляет дельты (текущий период − предыдущий) для каждого пользователя.
     * Ключи карты соответствуют именам метрик в шаблоне.
     */
    @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
    private Map<Long, Map<String, Number>> buildDeltas(List<UserMetrics> current,
                                                       Map<Long, UserMetrics> prev) {
        Map<Long, Map<String, Number>> result = new HashMap<>();
        for (UserMetrics m : current) {
            UserMetrics p = prev.get(m.getTrackedUserId());
            if (p == null) {
                continue;
            }
            Map<String, Number> d = new HashMap<>();
            d.put("mrMerged", m.getMrMergedCount() - p.getMrMergedCount());
            d.put("linesAdded", m.getLinesAdded() - p.getLinesAdded());
            d.put("linesDeleted", m.getLinesDeleted() - p.getLinesDeleted());
            d.put("commits", m.getCommitsInMrCount() - p.getCommitsInMrCount());
            d.put("comments", m.getReviewCommentsWrittenCount() - p.getReviewCommentsWrittenCount());
            d.put("reviewed", m.getMrsReviewedCount() - p.getMrsReviewedCount());
            d.put("approvals", m.getApprovalsGivenCount() - p.getApprovalsGivenCount());
            d.put("activeDays", m.getActiveDaysCount() - p.getActiveDaysCount());
            if (m.getAvgTimeToMergeMinutes() != null && p.getAvgTimeToMergeMinutes() != null) {
                d.put("timeToMerge", m.getAvgTimeToMergeMinutes() - p.getAvgTimeToMergeMinutes());
            }
            result.put(m.getTrackedUserId(), d);
        }
        return result;
    }

    private int periodToDays(String period) {
        try {
            return PeriodType.valueOf(period).toDays();
        } catch (IllegalArgumentException e) {
            return PeriodType.LAST_30_DAYS.toDays();
        }
    }

    /**
     * Нормализует атрибуты пользователя от разных провайдеров в единый вид.
     * GitHub: login / name / avatar_url
     */
    private Map<String, Object> resolveUser(OAuth2AuthenticationToken authentication) {
        Map<String, Object> attrs = authentication.getPrincipal().getAttributes();
        String provider = authentication.getAuthorizedClientRegistrationId();

        String username = "github".equals(provider)
            ? (String) attrs.get("login")
            : (String) attrs.get("username");

        return Map.of(
            "name", attrs.getOrDefault("name", username),
            "username", username != null ? username : "",
            "avatarUrl", attrs.getOrDefault("avatar_url", ""),
            "provider", provider
        );
    }
}
