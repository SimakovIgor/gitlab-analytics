package io.simakov.analytics.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "merge_request_approval")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MergeRequestApproval {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merge_request_id",
            nullable = false)
    private Long mergeRequestId;

    @Column(name = "approved_by_gitlab_user_id")
    private Long approvedByGitlabUserId;

    @Column(name = "approved_by_username")
    private String approvedByUsername;

    @Column(name = "approved_by_name")
    private String approvedByName;

    @Column(name = "approved_at_gitlab")
    private Instant approvedAtGitlab;
}
