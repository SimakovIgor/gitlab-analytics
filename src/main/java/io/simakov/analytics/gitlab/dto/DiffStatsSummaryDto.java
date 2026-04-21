package io.simakov.analytics.gitlab.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Aggregate diff statistics returned by GitLab 14.10+ in the MR object
 * under {@code diff_stats_summary}. These values are computed server-side
 * and include all files — including those marked {@code too_large} in the
 * per-file {@code /diffs} endpoint. Using this field avoids under-counting
 * line changes for large MRs.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DiffStatsSummaryDto(int additions, int deletions) {

}
