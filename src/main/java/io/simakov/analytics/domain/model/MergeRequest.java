package io.simakov.analytics.domain.model;

import io.simakov.analytics.domain.model.enums.MrState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Entity
@Table(name = "merge_request")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MergeRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tracked_project_id",
            nullable = false)
    private Long trackedProjectId;

    /**
     * GitLab global MR id (mr.id) — unique across the instance
     */
    @Column(name = "gitlab_mr_id",
            nullable = false)
    private Long gitlabMrId;

    /**
     * GitLab project-scoped MR iid (mr.iid) — used in API URLs
     */
    @Column(name = "gitlab_mr_iid",
            nullable = false)
    private Long gitlabMrIid;

    @Column(length = 1024)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false,
            length = 50)
    private MrState state;

    @Column(name = "author_gitlab_user_id")
    private Long authorGitlabUserId;

    @Column(name = "author_username")
    private String authorUsername;

    @Column(name = "author_name")
    private String authorName;

    @Column(name = "created_at_gitlab",
            nullable = false)
    private Instant createdAtGitlab;

    @Column(name = "merged_at_gitlab")
    private Instant mergedAtGitlab;

    @Column(name = "closed_at_gitlab")
    private Instant closedAtGitlab;

    @Column(name = "merged_by_gitlab_user_id")
    private Long mergedByGitlabUserId;

    @Column(nullable = false)
    private int additions;

    @Column(nullable = false)
    private int deletions;

    @Column(name = "changes_count",
            nullable = false)
    private int changesCount;

    @Column(name = "files_changed_count",
            nullable = false)
    private int filesChangedCount;

    /**
     * Net diff additions from GET /merge_requests/:iid/diffs — matches GitLab UI.
     * NULL if not yet fetched (requires fetchDiffStats=true in sync request).
     */
    @Column(name = "net_additions")
    private Integer netAdditions;

    /**
     * Net diff deletions from GET /merge_requests/:iid/diffs — matches GitLab UI.
     * NULL if not yet fetched (requires fetchDiffStats=true in sync request).
     */
    @Column(name = "net_deletions")
    private Integer netDeletions;

    @Column(name = "web_url",
            length = 1024)
    private String webUrl;

    @Column(name = "first_commit_at")
    private Instant firstCommitAt;

    @Column(name = "last_commit_at")
    private Instant lastCommitAt;

    @Column(name = "updated_at_gitlab")
    private Instant updatedAtGitlab;

    /**
     * The release this MR shipped in (set by ReleaseSyncStep based on merged_at window).
     */
    @Column(name = "release_tag_id")
    private Long releaseTagId;

    @CreationTimestamp
    @Column(name = "synced_at",
            nullable = false)
    private Instant syncedAt;
}
