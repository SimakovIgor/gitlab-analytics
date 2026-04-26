package io.simakov.analytics;

import com.icegreen.greenmail.util.GreenMail;
import io.simakov.analytics.domain.model.AppUser;
import io.simakov.analytics.domain.model.Workspace;
import io.simakov.analytics.domain.model.WorkspaceMember;
import io.simakov.analytics.domain.repository.AppUserRepository;
import io.simakov.analytics.domain.repository.WorkspaceMemberRepository;
import io.simakov.analytics.domain.repository.WorkspaceRepository;
import io.simakov.analytics.gitlab.client.GitLabApiClient;
import io.simakov.analytics.security.AppUserPrincipal;
import io.simakov.analytics.security.WorkspaceAwareSuccessHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestConfig.class)
public abstract class BaseIT {

    protected static final String TEST_TOKEN = "test-token";
    protected static final String BEARER_TEST_TOKEN = "Bearer " + TEST_TOKEN;

    @LocalServerPort
    protected int port;

    @Autowired
    protected TestRestTemplate restTemplate;

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected GreenMail greenMail;

    /**
     * GitLab is an external dependency — mock it so tests don't require a real GitLab instance.
     * Real behaviour is tested via unit tests in MetricCalculationServiceTest.
     */
    @MockBean
    protected GitLabApiClient gitLabApiClient;

    protected Long testWorkspaceId;
    protected MockHttpSession webSession;
    protected AppUser ownerUser;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private WorkspaceMemberRepository workspaceMemberRepository;

    @BeforeEach
    void resetMailbox() {
        greenMail.reset();
    }

    @BeforeEach
    void setUpWorkspace() {
        ownerUser = appUserRepository.save(AppUser.builder()
            .email("owner@test.com")
            .name("Test Owner")
            .lastLoginAt(Instant.now())
            .build());

        Workspace workspace = workspaceRepository.save(Workspace.builder()
            .name("Test Workspace")
            .slug("test-workspace")
            .ownerId(ownerUser.getId())
            .plan("FREE")
            .apiToken(TEST_TOKEN)
            .build());

        workspaceMemberRepository.save(WorkspaceMember.builder()
            .workspaceId(workspace.getId())
            .appUserId(ownerUser.getId())
            .role("OWNER")
            .build());

        testWorkspaceId = workspace.getId();

        webSession = new MockHttpSession();
        webSession.setAttribute(WorkspaceAwareSuccessHandler.SESSION_WORKSPACE_ID, testWorkspaceId);
    }

    /** Returns a RequestPostProcessor that authenticates as the workspace owner (AppUserPrincipal). */
    protected RequestPostProcessor ownerPrincipal() {
        return SecurityMockMvcRequestPostProcessors.user(new AppUserPrincipal(ownerUser));
    }

    protected HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, BEARER_TEST_TOKEN);
        return headers;
    }

    @AfterEach
    void cleanUpDatabase() {
        jdbcTemplate.execute("TRUNCATE TABLE merge_request_approval CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE merge_request_note CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE merge_request_discussion CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE merge_request_commit CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE merge_request CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE jira_incident CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE release_tag CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE sync_job CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE metric_snapshot CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE tracked_user_alias CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE tracked_user CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE team CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE tracked_project CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE git_source CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE workspace_invite CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE workspace_member CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE workspace CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE ai_insight_cache CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE password_reset_token CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE app_user CASCADE");
    }
}
