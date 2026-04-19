package io.simakov.analytics.workspace;

import io.simakov.analytics.domain.model.Workspace;
import io.simakov.analytics.domain.model.WorkspaceMember;
import io.simakov.analytics.domain.model.enums.WorkspaceRole;
import io.simakov.analytics.domain.repository.WorkspaceMemberRepository;
import io.simakov.analytics.domain.repository.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkspaceService {

    private static final Pattern NON_SLUG_CHARS = Pattern.compile("[^a-z0-9-]");
    private static final Pattern CONSECUTIVE_DASHES = Pattern.compile("-{2,}");
    private static final int SLUG_MAX_LENGTH = 60;

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;

    private static String slugify(String input) {
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFD)
            .replaceAll("\\p{InCombiningDiacriticalMarks}", "");
        String lower = normalized.toLowerCase(Locale.ROOT);
        String dashed = NON_SLUG_CHARS.matcher(lower).replaceAll("-");
        String clean = CONSECUTIVE_DASHES.matcher(dashed).replaceAll("-").replaceAll("^-|-$", "");
        if (clean.isEmpty()) {
            clean = "workspace";
        }
        return clean.substring(0, Math.min(clean.length(), SLUG_MAX_LENGTH));
    }

    @Transactional
    public Workspace createWorkspace(String name,
                                     Long ownerAppUserId) {
        String slug = generateUniqueSlug(name);
        String apiToken = UUID.randomUUID().toString().replace("-", "");

        Workspace workspace = workspaceRepository.save(Workspace.builder()
            .name(name)
            .slug(slug)
            .ownerId(ownerAppUserId)
            .plan("FREE")
            .apiToken(apiToken)
            .build());

        workspaceMemberRepository.save(WorkspaceMember.builder()
            .workspaceId(workspace.getId())
            .appUserId(ownerAppUserId)
            .role(WorkspaceRole.OWNER.name())
            .build());

        log.info("Created workspace id={} slug={} owner={}", workspace.getId(), slug, ownerAppUserId);
        return workspace;
    }

    private String generateUniqueSlug(String name) {
        String base = slugify(name);
        if (!workspaceRepository.existsBySlug(base)) {
            return base;
        }
        // append short random suffix until unique
        String candidate;
        do {
            candidate = base.substring(0, Math.min(base.length(), SLUG_MAX_LENGTH - 5))
                + "-" + UUID.randomUUID().toString().substring(0, 4);
        } while (workspaceRepository.existsBySlug(candidate));
        return candidate;
    }
}
