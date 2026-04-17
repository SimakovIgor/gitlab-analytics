package io.simakov.analytics.api.controller;

import io.simakov.analytics.api.dto.request.CreateGitSourceRequest;
import io.simakov.analytics.api.dto.response.GitSourceResponse;
import io.simakov.analytics.api.exception.ResourceNotFoundException;
import io.simakov.analytics.api.mapper.GitSourceMapper;
import io.simakov.analytics.domain.model.GitSource;
import io.simakov.analytics.domain.repository.GitSourceRepository;
import io.simakov.analytics.encryption.EncryptionService;
import io.simakov.analytics.gitlab.client.GitLabApiClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/sources/gitlab")
@RequiredArgsConstructor
@Tag(name = "GitLab Sources",
     description = "Manage GitLab instance connections")
public class GitSourceController {

    private final GitSourceRepository gitSourceRepository;
    private final EncryptionService encryptionService;
    private final GitLabApiClient gitLabApiClient;
    private final GitSourceMapper gitSourceMapper;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Register a GitLab source")
    public GitSourceResponse create(@RequestBody @Valid CreateGitSourceRequest request) {
        GitSource source = GitSource.builder()
            .name(request.name())
            .baseUrl(request.baseUrl().stripTrailing())
            .tokenEncrypted(encryptionService.encrypt(request.token()))
            .build();
        return gitSourceMapper.toResponse(gitSourceRepository.save(source));
    }

    @PostMapping("/{id}/test")
    @Operation(summary = "Test connectivity to a GitLab instance")
    public Map<String, Object> test(@PathVariable Long id) {
        GitSource source = gitSourceRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("GitSource", id));

        String token = encryptionService.decrypt(source.getTokenEncrypted());
        var user = gitLabApiClient.getCurrentUser(source.getBaseUrl(), token);

        return Map.of(
            "status", "ok",
            "gitlabUserId", user.id(),
            "username", user.username(),
            "name", user.name()
        );
    }
}
