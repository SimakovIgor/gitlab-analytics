package io.simakov.analytics.web;

import io.simakov.analytics.domain.model.TrackedUser;
import io.simakov.analytics.domain.model.TrackedUserAlias;
import io.simakov.analytics.domain.repository.MergeRequestCommitRepository;
import io.simakov.analytics.domain.repository.TrackedUserAliasRepository;
import io.simakov.analytics.domain.repository.TrackedUserRepository;
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
    private final TrackedUserRepository trackedUserRepository;
    private final TrackedUserAliasRepository aliasRepository;

    /**
     * Second pass: contributors sharing the same normalized display name are merged.
     * The one with the most commits becomes the primary entry; others become mergedEmails.
     * Guarded by MIN_NAME_LENGTH_FOR_MERGE to avoid collapsing generic short names.
     */
    @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
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
                .filter(x -> !x.email().equals(primary.email()))
                .map(DiscoveredContributor::email)
                .toList();

            Set<String> allRepos = new LinkedHashSet<>(primary.repoNames());
            group.forEach(x -> allRepos.addAll(x.repoNames()));

            int totalCommits = group.stream().mapToInt(DiscoveredContributor::commitCount).sum();
            boolean tracked = group.stream().anyMatch(DiscoveredContributor::alreadyTracked);

            result.add(new DiscoveredContributor(
                primary.email(),
                primary.primaryName(),
                primary.allNames(),
                totalCommits,
                new ArrayList<>(allRepos),
                tracked,
                mergedEmails
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
            List.of()
        );
    }

    @Transactional(readOnly = true)
    @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
    public List<DiscoveredContributor> discover() {
        Set<String> trackedEmails = buildTrackedEmailSet();

        // email → {name → totalCommits}
        Map<String, Map<String, Long>> nameCommits = new LinkedHashMap<>();
        // email → repos
        Map<String, Set<String>> emailRepos = new LinkedHashMap<>();

        for (Object[] row : commitRepository.findContributorRows()) {
            String email = ((String) row[0]).toLowerCase(Locale.ROOT).strip();
            String name = row[1] != null
                ? (String) row[1]
                : "";
            long count = ((Number) row[2]).longValue();
            String repo = row[3] != null
                ? (String) row[3]
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

        return mergeByName(byEmail);
    }

    private Set<String> buildTrackedEmailSet() {
        Set<String> emails = trackedUserRepository.findAll().stream()
            .map(TrackedUser::getEmail)
            .filter(e -> e != null && !e.isBlank())
            .map(e -> e.toLowerCase(Locale.ROOT).strip())
            .collect(Collectors.toSet());

        aliasRepository.findAll().stream()
            .map(TrackedUserAlias::getEmail)
            .filter(e -> e != null && !e.isBlank())
            .map(e -> e.toLowerCase(Locale.ROOT).strip())
            .forEach(emails::add);

        return emails;
    }
}
