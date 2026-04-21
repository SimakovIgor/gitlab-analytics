package io.simakov.analytics.insights.evaluator;

import io.simakov.analytics.domain.model.MergeRequest;
import io.simakov.analytics.insights.InsightEvaluator;
import io.simakov.analytics.insights.InsightProperties;
import io.simakov.analytics.insights.model.InsightContext;
import io.simakov.analytics.insights.model.InsightRule;
import io.simakov.analytics.insights.model.TeamInsight;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Fires when there are open MRs older than {@code stuckMrHours}.
 * "Stuck" is defined purely by age — an open MR that has been sitting
 * without being merged or closed for longer than the threshold.
 */
@Component
@RequiredArgsConstructor
public class StuckMrsEvaluator implements InsightEvaluator {

    private final InsightProperties props;

    @Override
    public List<TeamInsight> evaluate(InsightContext ctx) {
        if (ctx.openMrs().isEmpty()) {
            return List.of();
        }

        Instant cutoff = Instant.now().minus(Duration.ofHours(props.getStuckMrHours()));

        List<MergeRequest> stuckMrs = ctx.openMrs().stream()
            .filter(mr -> mr.getCreatedAtGitlab().isBefore(cutoff))
            .toList();

        if (stuckMrs.isEmpty()) {
            return List.of();
        }

        List<Long> affectedUserIds = stuckMrs.stream()
            .map(MergeRequest::getAuthorGitlabUserId)
            .filter(Objects::nonNull)
            .map(gitlabId -> ctx.gitlabUserIdToTrackedUserId().get(gitlabId))
            .filter(Objects::nonNull)
            .distinct()
            .collect(Collectors.toList());

        long maxHours = stuckMrs.stream()
            .mapToLong(mr -> Duration.between(mr.getCreatedAtGitlab(), Instant.now()).toHours())
            .max()
            .orElse(0);

        String title = String.format(
            "%d MR %s открытыми больше %d ч",
            stuckMrs.size(),
            stuckMrs.size() == 1 ? "висит" : "висят",
            props.getStuckMrHours()
        );
        String body = String.format(
            "Самый долгий открыт %d ч назад. Зависшие MR увеличивают lead time и блокируют смежные изменения."
                + " Рекомендуется провести ревью или закрыть неактуальные.",
            maxHours
        );

        return List.of(TeamInsight.of(InsightRule.STUCK_MRS, title, body, affectedUserIds));
    }
}
