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
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Entity
@Table(name = "merge_request_commit")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MergeRequestCommit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merge_request_id",
            nullable = false)
    private Long mergeRequestId;

    @Column(name = "gitlab_commit_sha",
            nullable = false,
            length = 64)
    private String gitlabCommitSha;

    @Column(name = "author_name")
    private String authorName;

    @Column(name = "author_email")
    private String authorEmail;

    @Column(name = "authored_date")
    private Instant authoredDate;

    @Column(name = "committed_date")
    private Instant committedDate;

    @Column(nullable = false)
    private int additions;

    @Column(nullable = false)
    private int deletions;

    @Column(name = "total_changes",
            nullable = false)
    private int totalChanges;

    @Column(name = "files_changed_count",
            nullable = false)
    private int filesChangedCount;

    @CreationTimestamp
    @Column(name = "created_at",
            nullable = false,
            updatable = false)
    private Instant createdAt;
}
