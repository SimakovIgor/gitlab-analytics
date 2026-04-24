package io.simakov.analytics.workspace;

import io.simakov.analytics.domain.model.WorkspaceInvite;
import io.simakov.analytics.domain.model.WorkspaceMember;
import io.simakov.analytics.domain.model.enums.WorkspaceRole;
import io.simakov.analytics.domain.repository.WorkspaceInviteRepository;
import io.simakov.analytics.domain.repository.WorkspaceMemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class InviteService {

    private static final int INVITE_TTL_DAYS = 7;

    private final WorkspaceInviteRepository inviteRepository;
    private final WorkspaceMemberRepository memberRepository;

    @Transactional
    public WorkspaceInvite createInvite(Long workspaceId, Long createdByAppUserId) {
        String token = UUID.randomUUID().toString().replace("-", "");
        WorkspaceInvite invite = WorkspaceInvite.builder()
            .workspaceId(workspaceId)
            .token(token)
            .role(WorkspaceRole.MEMBER.name())
            .createdBy(createdByAppUserId)
            .expiresAt(Instant.now().plus(INVITE_TTL_DAYS, ChronoUnit.DAYS))
            .build();
        inviteRepository.save(invite);
        log.info("Created invite token for workspace={} by user={}", workspaceId, createdByAppUserId);
        return invite;
    }

    public Optional<WorkspaceInvite> findValid(String token) {
        return inviteRepository.findByToken(token)
            .filter(i -> i.getUsedAt() == null)
            .filter(i -> i.getExpiresAt().isAfter(Instant.now()));
    }

    @Transactional
    public void consumeInvite(WorkspaceInvite invite, Long appUserId) {
        if (memberRepository.existsByWorkspaceIdAndAppUserId(invite.getWorkspaceId(), appUserId)) {
            log.debug("User {} is already a member of workspace {}", appUserId, invite.getWorkspaceId());
            return;
        }
        memberRepository.save(WorkspaceMember.builder()
            .workspaceId(invite.getWorkspaceId())
            .appUserId(appUserId)
            .role(WorkspaceRole.MEMBER.name())
            .build());
        invite.setUsedAt(Instant.now());
        invite.setUsedByAppUserId(appUserId);
        inviteRepository.save(invite);
        log.info("User {} joined workspace {} via invite", appUserId, invite.getWorkspaceId());
    }
}
