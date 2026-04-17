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
    List<String> mergedEmails
) {

}
