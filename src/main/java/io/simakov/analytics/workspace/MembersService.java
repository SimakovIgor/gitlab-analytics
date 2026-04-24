package io.simakov.analytics.workspace;

import io.simakov.analytics.domain.model.AppUser;
import io.simakov.analytics.domain.model.WorkspaceInvite;
import io.simakov.analytics.domain.model.WorkspaceMember;
import io.simakov.analytics.domain.model.enums.WorkspaceRole;
import io.simakov.analytics.domain.repository.AppUserRepository;
import io.simakov.analytics.domain.repository.WorkspaceMemberRepository;
import io.simakov.analytics.web.dto.MemberDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MembersService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter
        .ofPattern("d MMM yyyy", new Locale("ru"))
        .withZone(ZoneId.of("UTC"));

    private final WorkspaceMemberRepository memberRepository;
    private final AppUserRepository appUserRepository;
    private final InviteService inviteService;

    public List<MemberDto> listMembers(Long workspaceId) {
        List<WorkspaceMember> members = memberRepository.findByWorkspaceId(workspaceId);
        Set<Long> userIds = members.stream().map(WorkspaceMember::getAppUserId).collect(Collectors.toSet());
        Map<Long, AppUser> usersById = appUserRepository.findAllById(userIds).stream()
            .collect(Collectors.toMap(AppUser::getId, Function.identity()));

        return members.stream()
            .map(m -> {
                AppUser user = usersById.get(m.getAppUserId());
                String name;
                if (user == null) {
                    name = "Unknown";
                } else if (user.getName() != null) {
                    name = user.getName();
                } else {
                    name = "@" + user.getGithubLogin();
                }
                String username = user != null ? user.getGithubLogin() : "";
                String avatarUrl = user != null && user.getAvatarUrl() != null
                    ? user.getAvatarUrl()
                    : "";
                String joined = m.getInvitedAt() != null
                    ? DATE_FMT.format(m.getInvitedAt())
                    : "—";
                return new MemberDto(
                    m.getAppUserId(),
                    name,
                    username,
                    avatarUrl,
                    m.getRole(),
                    joined,
                    WorkspaceRole.OWNER.name().equals(m.getRole())
                );
            })
            .toList();
    }

    public WorkspaceInvite createInvite(Long workspaceId, Long createdByAppUserId) {
        return inviteService.createInvite(workspaceId, createdByAppUserId);
    }

    public void removeMember(Long workspaceId, Long appUserId) {
        memberRepository.findByWorkspaceIdAndAppUserId(workspaceId, appUserId)
            .ifPresent(memberRepository::delete);
    }
}
