package io.simakov.analytics;

import io.simakov.analytics.gitlab.client.GitLabApiClient;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
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
    protected JdbcTemplate jdbcTemplate;

    /**
     * GitLab is an external dependency — mock it so tests don't require a real GitLab instance.
     * Real behaviour is tested via unit tests in MetricCalculationServiceTest.
     */
    @MockBean
    protected GitLabApiClient gitLabApiClient;

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
        jdbcTemplate.execute("TRUNCATE TABLE sync_job CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE metric_snapshot CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE tracked_user_alias CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE tracked_user CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE tracked_project CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE git_source CASCADE");
    }
}
