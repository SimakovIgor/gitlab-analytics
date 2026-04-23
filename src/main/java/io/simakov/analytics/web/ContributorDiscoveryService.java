package io.simakov.analytics.web;

import io.simakov.analytics.domain.repository.CommitContributorProjection;
import io.simakov.analytics.domain.repository.MergeRequestCommitRepository;
import io.simakov.analytics.domain.repository.MergeRequestRepository;
import io.simakov.analytics.domain.repository.MrAuthorWithCountProjection;
import io.simakov.analytics.domain.repository.TrackedUserAliasRepository;
import io.simakov.analytics.domain.repository.TrackedUserRepository;
import io.simakov.analytics.security.WorkspaceContext;
import io.simakov.analytics.util.BotDetector;
import io.simakov.analytics.web.dto.DiscoveredContributor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ContributorDiscoveryService {

    /**
     * Minimum normalized-name length to attempt same-person merging.
     */
    private static final int MIN_NAME_LENGTH_FOR_MERGE = 5;

    private final MergeRequestCommitRepository commitRepository;
    private final MergeRequestRepository mergeRequestRepository;
    private final TrackedUserRepository trackedUserRepository;
    private final TrackedUserAliasRepository aliasRepository;

    /**
     * Second pass: contributors sharing the same normalized display name are merged.
     * The one with the most commits becomes the primary entry; others become mergedEmails.
     * Guarded by MIN_NAME_LENGTH_FOR_MERGE to avoid collapsing generic short names.
     */
    private static List<DiscoveredContributor> mergeByName(List<DiscoveredContributor> source) {
        // normalized name → contributors with that name
        Map<String, List<DiscoveredContributor>> byName = new LinkedHashMap<>();
        for (DiscoveredContributor c : source) {
            String key = normalizeName(c.primaryName());
            if (key.length() >= MIN_NAME_LENGTH_FOR_MERGE) {
                byName.computeIfAbsent(key, k -> new ArrayList<>()).add(c);
            }
        }

        Set<String> consumed = new HashSet<>();
        List<DiscoveredContributor> result = new ArrayList<>();

        for (DiscoveredContributor c : source) {
            if (consumed.contains(c.email())) {
                continue;
            }
            String key = normalizeName(c.primaryName());
            List<DiscoveredContributor> group = byName.getOrDefault(key, List.of());

            if (group.size() <= 1) {
                result.add(c);
                continue;
            }

            // Primary = the one with most commits (list is already sorted desc)
            DiscoveredContributor primary = group.stream()
                .max(Comparator.comparingInt(DiscoveredContributor::commitCount))
                .orElse(c);

            List<String> mergedEmails = group.stream()
                .map(DiscoveredContributor::email)
                .filter(email -> !email.equals(primary.email()))
                .toList();

            Set<String> allRepos = new LinkedHashSet<>(primary.repoNames());
            group.forEach(x -> allRepos.addAll(x.repoNames()));

            int totalCommits = group.stream().mapToInt(DiscoveredContributor::commitCount).sum();
            boolean tracked = group.stream().anyMatch(DiscoveredContributor::alreadyTracked);

            boolean bot = group.stream().allMatch(DiscoveredContributor::suspectedBot);
            long totalMrs = group.stream().mapToLong(DiscoveredContributor::mrCount).sum();
            result.add(new DiscoveredContributor(
                primary.email(),
                primary.primaryName(),
                primary.allNames(),
                totalCommits,
                new ArrayList<>(allRepos),
                tracked,
                mergedEmails,
                bot,
                totalMrs
            ));
            group.forEach(x -> consumed.add(x.email()));
        }

        return result;
    }

    private static String normalizeName(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9а-яё]", "");
    }

    private static DiscoveredContributor toContributor(Map.Entry<String, Map<String, Long>> entry,
                                                       Map<String, Set<String>> emailRepos,
                                                       Set<String> trackedEmails) {
        String email = entry.getKey();
        Map<String, Long> counts = entry.getValue();

        String primaryName = counts.entrySet().stream()
            .max(Comparator.comparingLong(Map.Entry::getValue))
            .map(Map.Entry::getKey)
            .orElse("");

        List<String> allNames = counts.keySet().stream()
            .filter(n -> !n.equals(primaryName) && !n.isBlank())
            .sorted()
            .toList();

        int totalCommits = counts.values().stream().mapToInt(Long::intValue).sum();

        return new DiscoveredContributor(
            email,
            primaryName,
            allNames,
            totalCommits,
            new ArrayList<>(emailRepos.getOrDefault(email, Set.of())),
            trackedEmails.contains(email),
            List.of(),
            BotDetector.isSuspectedBot(primaryName, null, email),
            0
        );
    }

    @Transactional(readOnly = true)
    public List<DiscoveredContributor> discover() {
        Long workspaceId = WorkspaceContext.get();
        Set<String> trackedEmails = buildTrackedEmailSet(workspaceId);

        // email → {name → totalCommits}
        Map<String, Map<String, Long>> nameCommits = new LinkedHashMap<>();
        // email → repos
        Map<String, Set<String>> emailRepos = new LinkedHashMap<>();

        for (CommitContributorProjection row : commitRepository.findContributorRowsByWorkspaceId(workspaceId)) {
            if (row.getAuthorEmail() == null) {
                continue;
            }
            String email = row.getAuthorEmail().toLowerCase(Locale.ROOT).strip();
            String name = row.getAuthorName() != null
                ? row.getAuthorName()
                : "";
            long count = row.getCommitCount();
            String repo = row.getRepoName() != null
                ? row.getRepoName()
                : "";

            nameCommits.computeIfAbsent(email, k -> new HashMap<>())
                .merge(name, count, Long::sum);
            emailRepos.computeIfAbsent(email, k -> new LinkedHashSet<>())
                .add(repo);
        }

        List<DiscoveredContributor> byEmail = nameCommits.entrySet().stream()
            .map(entry -> toContributor(entry, emailRepos, trackedEmails))
            .sorted(Comparator.comparingInt(DiscoveredContributor::commitCount).reversed())
            .collect(Collectors.toCollection(ArrayList::new));

        List<DiscoveredContributor> merged = mergeByName(byEmail);

        // Add MR-based authors not found via commits (e.g. placeholder/bot accounts)
        Set<String> seenNames = merged.stream()
            .map(c -> normalizeName(c.primaryName()))
            .collect(Collectors.toSet());
        appendMrAuthors(workspaceId, seenNames, merged);

        return merged;
    }

    /**
     * Appends MR authors (by gitlab_user_id) that were not found via commit-based discovery.
     * This catches placeholder/bot accounts that have MRs but no commits.
     */
    private void appendMrAuthors(Long workspaceId,
                                 Set<String> seenNormalizedNames,
                                 List<DiscoveredContributor> result) {
        List<MrAuthorWithCountProjection> mrAuthors =
            mergeRequestRepository.findDistinctAuthorsByWorkspace(workspaceId);
        for (MrAuthorWithCountProjection author : mrAuthors) {
            String name = author.getAuthorName() != null ? author.getAuthorName() : author.getAuthorUsername();
            String username = author.getAuthorUsername();
            // Use username as unique key — multiple users can share the same display name
            String uniqueKey = normalizeName(username);
            if (seenNormalizedNames.contains(uniqueKey) || seenNormalizedNames.contains(normalizeName(name))) {
                continue;
            }
            seenNormalizedNames.add(uniqueKey);
            boolean tracked = aliasRepository.existsByGitlabUserId(author.getAuthorGitlabUserId());
            result.add(new DiscoveredContributor(
                username + "@gitlab",
                name,
                List.of(),
                0,
                List.of(),
                tracked,
                List.of(),
                BotDetector.isSuspectedBot(name, username),
                author.getMrCount()
            ));
        }
    }

    private Set<String> buildTrackedEmailSet(Long workspaceId) {
        return trackedUserRepository.findAllTrackedEmailsByWorkspaceId(workspaceId).stream()
            .map(String::strip)
            .collect(Collectors.toSet());
    }
}
