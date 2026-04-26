package io.simakov.analytics.insights.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.simakov.analytics.domain.model.AiInsightRecord;
import io.simakov.analytics.domain.model.TrackedProject;
import io.simakov.analytics.domain.model.TrackedUser;
import io.simakov.analytics.domain.model.enums.PeriodType;
import io.simakov.analytics.domain.repository.AiInsightCacheRepository;
import io.simakov.analytics.domain.repository.TrackedProjectRepository;
import io.simakov.analytics.domain.repository.TrackedUserRepository;
import io.simakov.analytics.insights.model.TeamInsight;
import io.simakov.analytics.metrics.MetricCalculationService;
import io.simakov.analytics.metrics.model.UserMetrics;
import io.simakov.analytics.util.DateTimeUtils;
import io.simakov.analytics.web.DoraService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Cache-first AI insights service.
 * <p>
 * On each page load, returns the cached result if it is within the TTL window.
 * Generation is only triggered when the cache is stale or explicitly refreshed.
 * When {@code app.anthropic.api-key} is blank, the feature is disabled and an
 * empty list is returned without touching the cache.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiInsightService {

    private final AnthropicProperties props;
    private final AnthropicClient anthropicClient;
    private final AiInsightGenerationService generationService;
    private final AiInsightCacheRepository cacheRepository;
    private final MetricCalculationService metricCalculationService;
    private final TrackedUserRepository trackedUserRepository;
    private final TrackedProjectRepository trackedProjectRepository;
    private final DoraService doraService;
    private final ObjectMapper objectMapper;

    /**
     * Returns cached AI insights for the given parameters.
     * Does NOT trigger generation — call only if within TTL.
     * Returns empty list when cache is absent or stale.
     * Cache reads work even when the API key is not currently configured.
     */
    @Transactional(readOnly = true)
    public List<AiInsightDto> getCached(Long workspaceId, String period, List<Long> projectIds) {
        String hash = computeHash(projectIds);
        Optional<AiInsightRecord> cached = cacheRepository
            .findByWorkspaceIdAndPeriodAndProjectIdsHash(workspaceId, period, hash);

        if (cached.isEmpty()) {
            return List.of();
        }
        AiInsightRecord cacheRecord = cached.get();
        if (isStale(cacheRecord)) {
            return List.of();
        }
        return deserialize(cacheRecord.getInsightsJson());
    }

    /**
     * Returns the generation timestamp for the cached entry, or {@code null} if absent/stale.
     */
    @Transactional(readOnly = true)
    public Instant getCachedGeneratedAt(Long workspaceId,
                                        String period,
                                        List<Long> projectIds) {
        String hash = computeHash(projectIds);
        return cacheRepository
            .findByWorkspaceIdAndPeriodAndProjectIdsHash(workspaceId, period, hash)
            .filter(r -> !isStale(r))
            .map(AiInsightRecord::getGeneratedAt)
            .orElse(null);
    }

    /**
     * Forces regeneration: deletes the cache entry, calls the API, saves and returns the result.
     * Requires the caller to pass the already-computed {@code algoInsights} so generation
     * can skip rules that are already covered.
     *
     * @return list of freshly generated AI insights; empty list on API error
     */
    @Transactional
    public List<AiInsightDto> refresh(Long workspaceId,
                                      String period,
                                      List<Long> projectIds,
                                      List<TeamInsight> algoInsights) {
        if (!props.isEnabled()) {
            log.warn("AI insights refresh requested but feature is disabled (no API key)");
            return List.of();
        }

        String hash = computeHash(projectIds);
        cacheRepository.deleteByWorkspaceIdAndPeriodAndProjectIdsHash(workspaceId, period, hash);

        return generate(workspaceId, period, projectIds, hash, algoInsights);
    }

    // ── private helpers ──────────────────────────────────────────────────────

    @SuppressWarnings("checkstyle:IllegalCatch")
    private List<AiInsightDto> generate(Long workspaceId,
                                        String period,
                                        List<Long> resolvedProjectIds,
                                        String hash,
                                        List<TeamInsight> algoInsights) {
        try {
            int days = PeriodType.valueOf(period).toDays();
            Instant now = DateTimeUtils.now();
            Instant dateFrom = DateTimeUtils.minusDays(now, days);

            List<TrackedUser> users = trackedUserRepository.findAllByWorkspaceId(workspaceId);
            List<Long> userIds = users.stream().map(TrackedUser::getId).toList();

            List<Long> projectIdsToUse = resolvedProjectIds.isEmpty()
                ? trackedProjectRepository.findAllByWorkspaceId(workspaceId).stream()
                .map(TrackedProject::getId).toList()
                : resolvedProjectIds;

            Map<Long, UserMetrics> currentMetrics =
                metricCalculationService.calculate(projectIdsToUse, userIds, dateFrom, now);

            Double leadTimeDays = doraService.computeLeadTimeMedianDays(projectIdsToUse, dateFrom, now);
            Double deploysPerDay = doraService.computeDeploysPerDay(projectIdsToUse, dateFrom, now, days);
            Double mttrHours = doraService.computeMttrHours(projectIdsToUse, dateFrom);

            Map<String, Double> doraContext = Map.of(
                "leadTimeDays", leadTimeDays != null ? leadTimeDays : -1.0,
                "deploysPerDay", deploysPerDay != null ? deploysPerDay : -1.0,
                "mttrHours", mttrHours != null ? mttrHours : -1.0
            );

            String periodLabel = days + " days";
            AiInsightGenerationService.AiInsightGenerationResult result =
                generationService.generate(users.size(), periodLabel, currentMetrics, doraContext, algoInsights);

            String json = objectMapper.writeValueAsString(result.insights());
            AiInsightRecord newRecord = AiInsightRecord.builder()
                .workspaceId(workspaceId)
                .period(period)
                .projectIdsHash(hash)
                .insightsJson(json)
                .tokensUsed(result.tokensUsed())
                .generatedAt(DateTimeUtils.now())
                .build();
            cacheRepository.save(newRecord);

            log.info("Generated and cached {} AI insights for workspace {} / {} (hash={})",
                result.insights().size(), workspaceId, period, hash);
            return result.insights();

        } catch (Exception e) {
            log.error("AI insight generation failed for workspace {}: {}", workspaceId, e.getMessage(), e);
            return List.of();
        }
    }

    private boolean isStale(AiInsightRecord cacheRecord) {
        return cacheRecord.getGeneratedAt().isBefore(
            Instant.now().minus(props.getCacheTtlHours(), ChronoUnit.HOURS));
    }

    private List<AiInsightDto> deserialize(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<AiInsightDto>>() {
            });
        } catch (Exception e) {
            log.warn("Failed to deserialize cached AI insights: {}", e.getMessage());
            return List.of();
        }
    }

    private static String computeHash(List<Long> projectIds) {
        if (projectIds == null || projectIds.isEmpty()) {
            return "all";
        }
        String joined = projectIds.stream().sorted().map(Object::toString).reduce("", (a, b) -> a + "," + b);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(joined.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            return joined.substring(0, Math.min(joined.length(), 64));
        }
    }
}
