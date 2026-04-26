package io.simakov.analytics.insights.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.simakov.analytics.insights.model.TeamInsight;
import io.simakov.analytics.metrics.model.UserMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.stream.Collectors;

/**
 * Builds the prompt from aggregated team metrics, calls the Anthropic API,
 * and parses the structured JSON response into {@link AiInsightDto} list.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiInsightGenerationService {

    private final AnthropicClient anthropicClient;
    private final ObjectMapper objectMapper;

    /**
     * Generates AI insights for the given aggregated context.
     *
     * @param teamSize       number of tracked users
     * @param periodLabel    human-readable period (e.g. "30 days")
     * @param currentMetrics per-user metrics map for the current period
     * @param doraContext    map of DORA values: leadTimeDays, deploysPerDay, mttrHours
     * @param triggeredRules list of already-triggered algorithmic insights
     * @return parsed list of AI insights; empty list on parse failure
     */
    public AiInsightGenerationResult generate(
        int teamSize,
        String periodLabel,
        Map<Long, UserMetrics> currentMetrics,
        Map<String, Double> doraContext,
        List<TeamInsight> triggeredRules
    ) {
        String prompt = buildPrompt(teamSize, periodLabel, currentMetrics, doraContext, triggeredRules);
        log.debug("Sending AI insights prompt ({} chars)", prompt.length());

        AnthropicClient.AnthropicResponse response = anthropicClient.complete(prompt);
        List<AiInsightDto> insights = parseInsights(response.text());
        return new AiInsightGenerationResult(insights, response.tokensUsed());
    }

    private String buildPrompt(
        int teamSize,
        String periodLabel,
        Map<Long, UserMetrics> currentMetrics,
        Map<String, Double> doraContext,
        List<TeamInsight> triggeredRules
    ) {
        Collection<UserMetrics> metrics = currentMetrics.values();

        int totalMrs = metrics.stream().mapToInt(UserMetrics::getMrMergedCount).sum();
        double avgMrSize = metrics.stream()
            .filter(m -> m.getMrMergedCount() > 0)
            .mapToDouble(UserMetrics::getAvgMrSizeLines)
            .average().orElse(0);
        OptionalDouble medianTtmOpt = metrics.stream()
            .filter(m -> m.getMedianTimeToMergeMinutes() != null && m.getMrMergedCount() > 0)
            .mapToDouble(m -> m.getMedianTimeToMergeMinutes() / 60.0)
            .average();
        double medianTtmHours = medianTtmOpt.orElse(0);
        double avgReviewComments = metrics.stream()
            .filter(m -> m.getMrsReviewedCount() > 0)
            .mapToDouble(UserMetrics::getCommentsPerReviewedMr)
            .average().orElse(0);

        String triggeredSection = triggeredRules.isEmpty()
            ? "None"
            : triggeredRules.stream()
              .map(r -> "- " + r.rule().code() + ": " + r.title())
              .collect(Collectors.joining("\n"));

        Double leadTime = doraContext.get("leadTimeDays");
        Double deploysPerDay = doraContext.get("deploysPerDay");
        Double mttr = doraContext.get("mttrHours");

        String avgPerDev = teamSize > 0 ? String.format("%.1f", (double) totalMrs / teamSize) : "0";
        String leadTimeStr = leadTime != null ? String.format("%.1f days", leadTime) : "N/A";
        String deploysStr = deploysPerDay != null ? String.format("%.2f deploys/day", deploysPerDay) : "N/A";
        String mttrStr = mttr != null ? String.format("%.1f hours", mttr) : "N/A";

        return String.format(
            "You are an engineering analytics assistant for a software development team.%n"
                + "Analyze the following team metrics and provide 3–5 actionable insights in RUSSIAN.%n%n"
                + "Team metrics (period: %s):%n"
                + "- Team size: %d developers%n"
                + "- Merged MRs: %d (avg per dev: %s)%n"
                + "- Median Time to Merge: %.1fh%n"
                + "- Avg MR size: %.0f lines%n"
                + "- Avg review comments per MR: %.1f%n"
                + "- DORA Lead Time for Changes: %s%n"
                + "- DORA Deploy Frequency: %s%n"
                + "- DORA MTTR: %s%n%n"
                + "Algorithmic rules already triggered (do NOT duplicate these):%n"
                + "%s%n%n"
                + "Provide strategic, non-obvious insights about risks, patterns, or improvement opportunities.%n"
                + "Each insight must be actionable — suggest a concrete step.%n"
                + "Respond with ONLY a JSON array, no other text:%n"
                + "[%n"
                + "  {\"kind\": \"warn|bad|good|info\", \"title\": \"short title (max 80 chars)\", \"body\": \"1-2 sentences\"},%n"
                + "  ...%n"
                + "]",
            periodLabel, teamSize, totalMrs, avgPerDev,
            medianTtmHours, avgMrSize, avgReviewComments,
            leadTimeStr, deploysStr, mttrStr, triggeredSection);
    }

    @SuppressWarnings("checkstyle:IllegalCatch")
    private List<AiInsightDto> parseInsights(String text) {
        try {
            // Claude sometimes wraps JSON in markdown code fences — strip them
            String cleaned = text.trim();
            if (cleaned.startsWith("```")) {
                int firstNewline = cleaned.indexOf('\n');
                int lastFence = cleaned.lastIndexOf("```");
                if (firstNewline > 0 && lastFence > firstNewline) {
                    cleaned = cleaned.substring(firstNewline + 1, lastFence).trim();
                }
            }
            List<AiInsightDto> parsed = objectMapper.readValue(
                cleaned, new TypeReference<>() {
                });
            log.info("Parsed {} AI insights", parsed.size());
            return parsed;
        } catch (Exception e) {
            log.warn("Failed to parse AI insights JSON: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Carries parsed insights and the total tokens consumed by the API call.
     */
    public record AiInsightGenerationResult(List<AiInsightDto> insights, int tokensUsed) {

    }
}
