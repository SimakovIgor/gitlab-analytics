package io.simakov.analytics.workspace;

import io.simakov.analytics.domain.model.WorkspaceMember;
import io.simakov.analytics.domain.model.enums.WorkspaceRole;
import io.simakov.analytics.domain.repository.WorkspaceMemberRepository;
import io.simakov.analytics.security.AppUserPrincipal;
import io.simakov.analytics.security.WorkspaceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * Centralised workspace permission checks.
 *
 * <p>Owner-only operations: add/remove projects, manage team, trigger sync, manage members.
 * Members (non-owners) have read-only access to analytics pages.
 */
@Service
@RequiredArgsConstructor
public class WorkspacePermissionService {

    private final WorkspaceMemberRepository memberRepository;

    /**
     * Throws {@link AccessDeniedException} if the currently authenticated user
     * is not an OWNER of the current workspace.
     */
    public void requireOwner() {
        Long workspaceId = WorkspaceContext.get();
        Long appUserId = currentAppUserId();
        memberRepository.findByWorkspaceIdAndAppUserId(workspaceId, appUserId)
            .filter(m -> WorkspaceRole.OWNER.name().equals(m.getRole()))
            .orElseThrow(() -> new AccessDeniedException(
                "Only workspace owners can perform this action"));
    }

    /** Returns the current user's role string in the current workspace, or {@code null} if not a member. */
    public String currentRole() {
        Long workspaceId = WorkspaceContext.get();
        Long appUserId = currentAppUserId();
        return memberRepository.findByWorkspaceIdAndAppUserId(workspaceId, appUserId)
            .map(WorkspaceMember::getRole)
            .orElse(null);
    }

    public boolean isOwner() {
        return WorkspaceRole.OWNER.name().equals(currentRole());
    }

    private Long currentAppUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AppUserPrincipal principal)) {
            throw new AccessDeniedException("Not authenticated");
        }
        return principal.getAppUser().getId();
    }
}
