package io.simakov.analytics.insights;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.simakov.analytics.BaseIT;
import io.simakov.analytics.domain.model.AiInsightRecord;
import io.simakov.analytics.domain.repository.AiInsightCacheRepository;
import io.simakov.analytics.insights.ai.AiInsightDto;
import io.simakov.analytics.insights.ai.AiInsightService;
import io.simakov.analytics.insights.ai.AnthropicProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AiInsightServiceTest extends BaseIT {

    @Autowired
    private AiInsightService aiInsightService;

    @Autowired
    private AiInsightCacheRepository cacheRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AnthropicProperties anthropicProperties;

    // ── getCached ────────────────────────────────────────────────────────────

    @Test
    void getCached_returnsEmptyWhenNoCacheEntry() {
        List<AiInsightDto> result = aiInsightService.getCached(testWorkspaceId, "LAST_30_DAYS", List.of());
        assertThat(result).isEmpty();
    }

    @Test
    void getCached_returnsInsightsFromFreshCache() throws Exception {
        List<AiInsightDto> stored = List.of(
            new AiInsightDto("warn", "Команда перегружена", "Рекомендуется балансировка нагрузки.")
        );
        saveCache(testWorkspaceId, "LAST_30_DAYS", "all", stored, Instant.now().minusSeconds(60));

        List<AiInsightDto> result = aiInsightService.getCached(testWorkspaceId, "LAST_30_DAYS", List.of());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).title()).isEqualTo("Команда перегружена");
        assertThat(result.get(0).kind()).isEqualTo("warn");
    }

    @Test
    void getCached_returnsEmptyForStaleCache() throws Exception {
        List<AiInsightDto> stored = List.of(new AiInsightDto("bad", "Old insight", "Body."));
        // Generated 25 hours ago (TTL = 24h)
        Instant staleTime = Instant.now().minus(25, ChronoUnit.HOURS);
        saveCache(testWorkspaceId, "LAST_30_DAYS", "all", stored, staleTime);

        List<AiInsightDto> result = aiInsightService.getCached(testWorkspaceId, "LAST_30_DAYS", List.of());

        assertThat(result).isEmpty();
    }

    @Test
    void getCached_isolatesWorkspaces() throws Exception {
        // Cache for workspace 999 (different workspace)
        List<AiInsightDto> stored = List.of(new AiInsightDto("info", "Other WS insight", "Body."));
        saveCache(999L, "LAST_30_DAYS", "all", stored, Instant.now());

        List<AiInsightDto> result = aiInsightService.getCached(testWorkspaceId, "LAST_30_DAYS", List.of());

        assertThat(result).isEmpty();
    }

    @Test
    void getCached_isolatesByPeriod() throws Exception {
        List<AiInsightDto> stored = List.of(new AiInsightDto("good", "90d insight", "Body."));
        saveCache(testWorkspaceId, "LAST_90_DAYS", "all", stored, Instant.now());

        // Ask for 30d — should return empty
        List<AiInsightDto> result = aiInsightService.getCached(testWorkspaceId, "LAST_30_DAYS", List.of());

        assertThat(result).isEmpty();
    }

    @Test
    void getCached_worksWithoutApiKeyConfigured() throws Exception {
        // Feature is disabled in test profile (no api-key), but cached results should still be readable
        assertThat(anthropicProperties.isEnabled()).isFalse();

        List<AiInsightDto> stored = List.of(new AiInsightDto("info", "Cached insight", "Body."));
        saveCache(testWorkspaceId, "LAST_30_DAYS", "all", stored, Instant.now());

        List<AiInsightDto> result = aiInsightService.getCached(testWorkspaceId, "LAST_30_DAYS", List.of());

        assertThat(result).hasSize(1);
    }

    // ── getCachedGeneratedAt ─────────────────────────────────────────────────

    @Test
    void getCachedGeneratedAt_returnsNullWhenNoCacheEntry() {
        Instant ts = aiInsightService.getCachedGeneratedAt(testWorkspaceId, "LAST_30_DAYS", List.of());
        assertThat(ts).isNull();
    }

    @Test
    void getCachedGeneratedAt_returnsTimestampForFreshCache() throws Exception {
        Instant generatedAt = Instant.now().minusSeconds(3600);
        saveCache(testWorkspaceId, "LAST_30_DAYS", "all", List.of(), generatedAt);

        Instant ts = aiInsightService.getCachedGeneratedAt(testWorkspaceId, "LAST_30_DAYS", List.of());

        assertThat(ts).isNotNull();
        assertThat(ts).isCloseTo(generatedAt, org.assertj.core.api.Assertions.within(1, ChronoUnit.SECONDS));
    }

    @Test
    void getCachedGeneratedAt_returnsNullForStaleCache() throws Exception {
        Instant staleTime = Instant.now().minus(25, ChronoUnit.HOURS);
        saveCache(testWorkspaceId, "LAST_30_DAYS", "all", List.of(), staleTime);

        Instant ts = aiInsightService.getCachedGeneratedAt(testWorkspaceId, "LAST_30_DAYS", List.of());

        assertThat(ts).isNull();
    }

    // ── refresh — disabled path ──────────────────────────────────────────────

    @Test
    void refresh_returnsEmptyWhenApiKeyNotConfigured() {
        // api-key is blank in test profile → isEnabled() == false
        assertThat(anthropicProperties.isEnabled()).isFalse();

        List<AiInsightDto> result = aiInsightService.refresh(testWorkspaceId, "LAST_30_DAYS", List.of(), List.of());

        assertThat(result).isEmpty();
        // No cache entry should be written when disabled
        assertThat(cacheRepository.findAll()).isEmpty();
    }

    @Test
    void refresh_preservesExistingCacheWhenDisabled() throws Exception {
        // Pre-populate the cache — refresh() exits early before deleting when disabled
        List<AiInsightDto> oldInsights = List.of(new AiInsightDto("info", "Old", "Old body."));
        saveCache(testWorkspaceId, "LAST_30_DAYS", "all", oldInsights, Instant.now().minusSeconds(3600));
        assertThat(cacheRepository.findAll()).hasSize(1);

        aiInsightService.refresh(testWorkspaceId, "LAST_30_DAYS", List.of(), List.of());

        // Cache preserved because disabled guard fires before delete
        assertThat(cacheRepository.findAll()).hasSize(1);
    }

    // ── project hash isolation ───────────────────────────────────────────────

    @Test
    void getCached_differentProjectSetsAreIsolated() throws Exception {
        List<AiInsightDto> insights1 = List.of(new AiInsightDto("warn", "Project 1 insight", "Body."));
        List<AiInsightDto> insights2 = List.of(new AiInsightDto("bad", "Project 2 insight", "Body."));

        saveCache(testWorkspaceId, "LAST_30_DAYS", computeTestHash(List.of(1L, 2L)), insights1, Instant.now());
        saveCache(testWorkspaceId, "LAST_30_DAYS", computeTestHash(List.of(3L)), insights2, Instant.now());

        List<AiInsightDto> result1 = aiInsightService.getCached(testWorkspaceId, "LAST_30_DAYS", List.of(1L, 2L));
        List<AiInsightDto> result2 = aiInsightService.getCached(testWorkspaceId, "LAST_30_DAYS", List.of(3L));

        assertThat(result1).extracting(AiInsightDto::title).containsExactly("Project 1 insight");
        assertThat(result2).extracting(AiInsightDto::title).containsExactly("Project 2 insight");
    }

    @Test
    void getCached_emptyProjectListMapsToAllHash() throws Exception {
        List<AiInsightDto> stored = List.of(new AiInsightDto("good", "All projects insight", "Body."));
        saveCache(testWorkspaceId, "LAST_30_DAYS", "all", stored, Instant.now());

        // Query with empty list → should match "all" hash
        List<AiInsightDto> result = aiInsightService.getCached(testWorkspaceId, "LAST_30_DAYS", List.of());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).title()).isEqualTo("All projects insight");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    @SuppressWarnings("checkstyle:IllegalCatch")
    private void saveCache(Long workspaceId, String period, String hash,
                           List<AiInsightDto> insights,
                           Instant generatedAt) {
        String json;
        try {
            json = objectMapper.writeValueAsString(insights);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialize insights", e);
        }
        cacheRepository.save(AiInsightRecord.builder()
            .workspaceId(workspaceId)
            .period(period)
            .projectIdsHash(hash)
            .insightsJson(json)
            .tokensUsed(100)
            .generatedAt(generatedAt)
            .build());
    }

    /**
     * Mirrors the hash logic in {@link AiInsightService} for test verification.
     */
    @SuppressWarnings("checkstyle:IllegalCatch")
    private static String computeTestHash(List<Long> projectIds) {
        if (projectIds == null || projectIds.isEmpty()) {
            return "all";
        }
        String joined = projectIds.stream().sorted().map(Object::toString).reduce("", (a, b) -> a + "," + b);
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(joined.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(bytes).substring(0, 16);
        } catch (Exception e) {
            return joined.substring(0, Math.min(joined.length(), 64));
        }
    }
}
