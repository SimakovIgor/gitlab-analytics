package io.simakov.analytics.web.controller;

import io.simakov.analytics.BaseIT;
import io.simakov.analytics.domain.model.MetricSnapshot;
import io.simakov.analytics.domain.model.TrackedUser;
import io.simakov.analytics.domain.model.enums.PeriodType;
import io.simakov.analytics.domain.model.enums.ScopeType;
import io.simakov.analytics.domain.repository.MetricSnapshotRepository;
import io.simakov.analytics.domain.repository.TrackedUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class HistoryChartTest extends BaseIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TrackedUserRepository trackedUserRepository;

    @Autowired
    private MetricSnapshotRepository metricSnapshotRepository;

    @Test
    void chartEndpointRedirectsToLoginWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/report/chart"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    void chartEndpointReturnsEmptyJsonWhenNoUsers() throws Exception {
        MvcResult result = mockMvc.perform(get("/report/chart")
                .session(webSession)
                .with(oauth2Login())
                .param("metric", "mr_merged_count")
                .param("period", "LAST_360_DAYS"))
            .andExpect(status().isOk())
            .andReturn();

        assertThat(result.getResponse().getContentAsString()).isEqualTo("{}");
    }

    @Test
    void chartEndpointReturnsDatasetForUserWithSnapshot() throws Exception {
        TrackedUser alice = trackedUserRepository.save(TrackedUser.builder()
            .workspaceId(testWorkspaceId)
            .displayName("Alice")
            .email("alice@example.com")
            .enabled(true)
            .build());

        metricSnapshotRepository.save(MetricSnapshot.builder()
            .workspaceId(testWorkspaceId)
            .trackedUserId(alice.getId())
            .periodType(PeriodType.LAST_30_DAYS)
            .scopeType(ScopeType.USER)
            .snapshotDate(LocalDate.now(ZoneOffset.UTC))
            .windowDays(30)
            .dateFrom(Instant.now().minusSeconds(30L * 86_400))
            .dateTo(Instant.now())
            .metricsJson("{\"mr_merged_count\":5}")
            .build());

        MvcResult result = mockMvc.perform(get("/report/chart")
                .session(webSession)
                .with(oauth2Login())
                .param("metric", "mr_merged_count")
                .param("period", "LAST_360_DAYS"))
            .andExpect(status().isOk())
            .andReturn();

        String json = result.getResponse().getContentAsString();
        assertThat(json).contains("\"labels\"");
        assertThat(json).contains("\"datasets\"");
        assertThat(json).contains("Alice");
    }

    @Test
    void chartEndpointFiltersOutDisabledUsers() throws Exception {
        trackedUserRepository.save(TrackedUser.builder()
            .workspaceId(testWorkspaceId)
            .displayName("Disabled User")
            .email("disabled@example.com")
            .enabled(false)
            .build());

        MvcResult result = mockMvc.perform(get("/report/chart")
                .session(webSession)
                .with(oauth2Login())
                .param("metric", "mr_merged_count")
                .param("period", "LAST_360_DAYS")
                .param("showInactive", "true"))
            .andExpect(status().isOk())
            .andReturn();

        assertThat(result.getResponse().getContentAsString()).isEqualTo("{}");
    }
}
