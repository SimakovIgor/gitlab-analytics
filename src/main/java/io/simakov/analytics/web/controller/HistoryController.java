package io.simakov.analytics.web.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.simakov.analytics.domain.model.MetricSnapshot;
import io.simakov.analytics.domain.model.TrackedUser;
import io.simakov.analytics.domain.repository.MetricSnapshotRepository;
import io.simakov.analytics.domain.repository.TrackedUserRepository;
import io.simakov.analytics.metrics.model.Metric;
import io.simakov.analytics.util.DateTimeUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

@Slf4j
@Controller
public class HistoryController {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final List<String> PALETTE = List.of(
        "#FC6D26", "#6554C0", "#00B8D9", "#36B37E",
        "#FF5630", "#FFAB00", "#0065FF", "#00875A"
    );
    private static final Map<String, String> METRIC_OPTIONS = Metric.chartOptions();
    private static final Set<String> MINUTES_METRICS = Metric.minuteKeys();

    private final TrackedUserRepository trackedUserRepository;
    private final MetricSnapshotRepository metricSnapshotRepository;
    private final ObjectMapper objectMapper;

    public HistoryController(TrackedUserRepository trackedUserRepository,
                             MetricSnapshotRepository metricSnapshotRepository,
                             ObjectMapper objectMapper) {
        this.trackedUserRepository = trackedUserRepository;
        this.metricSnapshotRepository = metricSnapshotRepository;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/history")
    public String history(OAuth2AuthenticationToken authentication,
                          @RequestParam(defaultValue = "mr_merged_count") String metric,
                          @RequestParam(defaultValue = "3") int months,
                          Model model) {
        if (authentication != null) {
            model.addAttribute("currentUser", resolveUser(authentication));
        }

        List<TrackedUser> users = trackedUserRepository.findAll()
            .stream().filter(TrackedUser::isEnabled).toList();

        LocalDate dateTo = DateTimeUtils.currentDateUtc();
        LocalDate dateFrom = dateTo.minusMonths(months);

        String chartJson = "{}";
        if (!users.isEmpty()) {
            List<Long> userIds = users.stream().map(TrackedUser::getId).toList();
            List<MetricSnapshot> snapshots = metricSnapshotRepository.findHistory(userIds, dateFrom, dateTo);
            chartJson = buildChartJson(snapshots, users, metric);
        }

        model.addAttribute("chartData", chartJson);
        model.addAttribute("selectedMetric", metric);
        model.addAttribute("selectedMonths", months);
        model.addAttribute("metricLabel", METRIC_OPTIONS.getOrDefault(metric, metric));
        model.addAttribute("metricOptions", METRIC_OPTIONS);

        return "history";
    }

    /**
     * Собирает JSON для Chart.js:
     * labels = отсортированные даты снапшотов,
     * datasets = по одному на каждого пользователя.
     */
    @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
    private String buildChartJson(List<MetricSnapshot> snapshots,
                                  List<TrackedUser> users,
                                  String metric) {
        // userId → (date → value)
        Map<Long, Map<String, Object>> byUser = new LinkedHashMap<>();
        for (TrackedUser u : users) {
            byUser.put(u.getId(), new TreeMap<>());
        }

        for (MetricSnapshot snap : snapshots) {
            Map<String, Object> dateValues = byUser.get(snap.getTrackedUserId());
            if (dateValues == null) {
                continue;
            }
            try {
                Map<String, Object> metrics = objectMapper.readValue(snap.getMetricsJson(), MAP_TYPE);
                Object value = metrics.get(metric);
                if (MINUTES_METRICS.contains(metric) && value instanceof Number n) {
                    value = Math.round(n.doubleValue() / 60.0 * 10.0) / 10.0;
                }
                dateValues.put(snap.getSnapshotDate().toString(), value);
            } catch (JsonProcessingException e) {
                log.warn("Failed to parse metrics_json for snapshot id={}", snap.getId());
            }
        }

        // Все уникальные даты (отсортированы через TreeMap)
        List<String> labels = byUser.values().stream()
            .flatMap(m -> m.keySet().stream())
            .distinct()
            .sorted()
            .toList();

        List<Map<String, Object>> datasets = new ArrayList<>();
        int colorIdx = 0;
        for (TrackedUser user : users) {
            Map<String, Object> dateValues = byUser.get(user.getId());
            List<Object> data = labels.stream()
                .map(date -> dateValues.getOrDefault(date, null))
                .toList();

            String color = PALETTE.get(colorIdx % PALETTE.size());
            colorIdx++;

            Map<String, Object> dataset = new LinkedHashMap<>();
            dataset.put("label", user.getDisplayName());
            dataset.put("data", data);
            dataset.put("borderColor", color);
            dataset.put("backgroundColor", color + "22");
            dataset.put("tension", 0.3);
            dataset.put("spanGaps", true);
            dataset.put("borderWidth", 2.5);
            dataset.put("pointRadius", 3);
            dataset.put("pointHoverRadius", 6);
            datasets.add(dataset);
        }

        Map<String, Object> chartData = new LinkedHashMap<>();
        chartData.put("labels", labels);
        chartData.put("datasets", datasets);

        try {
            return objectMapper.writeValueAsString(chartData);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize chart data", e);
            return "{}";
        }
    }

    private Map<String, Object> resolveUser(OAuth2AuthenticationToken authentication) {
        Map<String, Object> attrs = authentication.getPrincipal().getAttributes();
        String provider = authentication.getAuthorizedClientRegistrationId();
        String username = "github".equals(provider)
            ? (String) attrs.get("login")
            : (String) attrs.get("username");
        return Map.of(
            "name", attrs.getOrDefault("name", username),
            "username", username != null
                ? username
                : "",
            "avatarUrl", attrs.getOrDefault("avatar_url", ""),
            "provider", provider
        );
    }
}
