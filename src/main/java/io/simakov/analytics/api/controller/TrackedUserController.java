package io.simakov.analytics.api.controller;

import io.simakov.analytics.api.dto.request.AddUserAliasRequest;
import io.simakov.analytics.api.dto.request.CreateTrackedUserRequest;
import io.simakov.analytics.api.dto.request.UpdateTrackedUserRequest;
import io.simakov.analytics.api.dto.response.TrackedUserResponse;
import io.simakov.analytics.api.exception.ResourceNotFoundException;
import io.simakov.analytics.api.mapper.TrackedUserMapper;
import io.simakov.analytics.domain.model.TrackedUser;
import io.simakov.analytics.domain.model.TrackedUserAlias;
import io.simakov.analytics.domain.repository.TrackedUserAliasRepository;
import io.simakov.analytics.domain.repository.TrackedUserRepository;
import io.simakov.analytics.security.WorkspaceContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@Tag(name = "Tracked Users",
     description = "Manage team members to track")
public class TrackedUserController {

    private final TrackedUserRepository trackedUserRepository;
    private final TrackedUserAliasRepository aliasRepository;
    private final TrackedUserMapper trackedUserMapper;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a tracked user")
    public TrackedUserResponse create(@RequestBody @Valid CreateTrackedUserRequest request) {
        TrackedUser entity = trackedUserMapper.toEntity(request);
        entity.setWorkspaceId(WorkspaceContext.get());
        TrackedUser saved = trackedUserRepository.save(entity);
        return trackedUserMapper.toResponse(saved, List.of());
    }

    @GetMapping
    @Operation(summary = "List all tracked users with their aliases")
    public List<TrackedUserResponse> list() {
        Long workspaceId = WorkspaceContext.get();
        List<TrackedUser> users = trackedUserRepository.findAllByWorkspaceId(workspaceId);
        List<Long> userIds = users.stream().map(TrackedUser::getId).toList();
        Map<Long, List<TrackedUserAlias>> aliasesByUserId = aliasRepository.findByTrackedUserIdIn(userIds)
            .stream().collect(Collectors.groupingBy(TrackedUserAlias::getTrackedUserId));
        return users.stream()
            .map(user -> trackedUserMapper.toResponse(
                user, aliasesByUserId.getOrDefault(user.getId(), List.of())))
            .toList();
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Update a tracked user",
               description = "Partial update — only provided fields are changed. "
                   + "Use enabled=false to disable a user without deleting them.")
    public TrackedUserResponse update(@PathVariable Long id,
                                      @RequestBody @Valid UpdateTrackedUserRequest request) {
        Long workspaceId = WorkspaceContext.get();
        TrackedUser user = trackedUserRepository.findById(id)
            .filter(u -> workspaceId.equals(u.getWorkspaceId()))
            .orElseThrow(() -> new ResourceNotFoundException("TrackedUser", id));
        trackedUserMapper.updateEntity(request, user);
        trackedUserRepository.save(user);
        return trackedUserMapper.toResponse(user, aliasRepository.findByTrackedUserId(id));
    }

    @PostMapping("/{id}/aliases")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a GitLab identity alias to a tracked user")
    public TrackedUserResponse addAlias(@PathVariable Long id,
                                        @RequestBody @Valid AddUserAliasRequest request) {
        Long workspaceId = WorkspaceContext.get();
        TrackedUser user = trackedUserRepository.findById(id)
            .filter(u -> workspaceId.equals(u.getWorkspaceId()))
            .orElseThrow(() -> new ResourceNotFoundException("TrackedUser", id));
        aliasRepository.save(trackedUserMapper.toAlias(request, user));
        return trackedUserMapper.toResponse(user, aliasRepository.findByTrackedUserId(id));
    }
}
