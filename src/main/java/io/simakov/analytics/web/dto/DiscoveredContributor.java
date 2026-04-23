package io.simakov.analytics.web.dto;

import java.util.List;

public record DiscoveredContributor(
    String email,
    String primaryName,
    List<String> allNames,
    int commitCount,
    List<String> repoNames,
    boolean alreadyTracked,
    /** Other emails detected as the same person (matched by display name). */
    List<String> mergedEmails,
    /** True if heuristics suggest this is a bot, placeholder, or system account. */
    boolean suspectedBot,
    /** MR count for authors discovered via MR data (0 for commit-based discovery). */
    long mrCount
) {

}
