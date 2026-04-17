package io.simakov.analytics.api.controller;

import io.simakov.analytics.api.dto.request.AddUserAliasRequest;
import io.simakov.analytics.api.dto.request.CreateTrackedUserRequest;
import io.simakov.analytics.api.dto.request.UpdateTrackedUserRequest;
import io.simakov.analytics.api.dto.response.TrackedUserResponse;
import io.simakov.analytics.api.exception.ResourceNotFoundException;
import io.simakov.analytics.domain.model.TrackedUser;
import io.simakov.analytics.domain.model.TrackedUserAlias;
import io.simakov.analytics.domain.repository.TrackedUserAliasRepository;
import io.simakov.analytics.domain.repository.TrackedUserRepository;
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

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@Tag(name = "Tracked Users",
     description = "Manage team members to track")
public class TrackedUserController {

    private final TrackedUserRepository trackedUserRepository;
    private final TrackedUserAliasRepository aliasRepository;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a tracked user")
    public TrackedUserResponse create(@RequestBody @Valid CreateTrackedUserRequest request) {
        TrackedUser user = TrackedUser.builder()
            .displayName(request.displayName())
            .email(request.email())
            .enabled(true)
            .build();
        TrackedUser saved = trackedUserRepository.save(user);
        return TrackedUserResponse.from(saved, List.of());
    }

    @GetMapping
    @Operation(summary = "List all tracked users with their aliases")
    public List<TrackedUserResponse> list() {
        return trackedUserRepository.findAll().stream()
            .map(user -> {
                List<TrackedUserAlias> aliases = aliasRepository.findByTrackedUserId(user.getId());
                return TrackedUserResponse.from(user, aliases);
            })
            .toList();
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Update a tracked user",
               description = "Partial update — only provided fields are changed. "
                   + "Use enabled=false to disable a user without deleting them.")
    public TrackedUserResponse update(@PathVariable Long id,
                                      @RequestBody UpdateTrackedUserRequest request) {
        TrackedUser user = trackedUserRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("TrackedUser", id));

        if (request.displayName() != null) {
            user.setDisplayName(request.displayName());
        }
        if (request.email() != null) {
            user.setEmail(request.email());
        }
        if (request.enabled() != null) {
            user.setEnabled(request.enabled());
        }

        trackedUserRepository.save(user);
        List<TrackedUserAlias> aliases = aliasRepository.findByTrackedUserId(id);
        return TrackedUserResponse.from(user, aliases);
    }

    @PostMapping("/{id}/aliases")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a GitLab identity alias to a tracked user")
    public TrackedUserResponse addAlias(@PathVariable Long id,
                                        @RequestBody @Valid AddUserAliasRequest request) {
        TrackedUser user = trackedUserRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("TrackedUser", id));

        TrackedUserAlias alias = TrackedUserAlias.builder()
            .trackedUserId(user.getId())
            .gitlabUserId(request.gitlabUserId())
            .username(request.username())
            .email(request.email())
            .name(request.name())
            .build();
        aliasRepository.save(alias);

        List<TrackedUserAlias> aliases = aliasRepository.findByTrackedUserId(id);
        return TrackedUserResponse.from(user, aliases);
    }
}
