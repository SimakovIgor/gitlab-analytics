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
@Table(name = "merge_request_note")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MergeRequestNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merge_request_id",
            nullable = false)
    private Long mergeRequestId;

    /**
     * FK to merge_request_discussion — null if note is not part of a thread
     */
    @Column(name = "discussion_id")
    private Long discussionId;

    @Column(name = "gitlab_note_id",
            nullable = false)
    private Long gitlabNoteId;

    @Column(name = "author_gitlab_user_id")
    private Long authorGitlabUserId;

    @Column(name = "author_username")
    private String authorUsername;

    @Column(name = "author_name")
    private String authorName;

    @Column(columnDefinition = "TEXT")
    private String body;

    /**
     * System-generated notes (e.g. "added 2 commits"). Excluded from review metrics.
     */
    @Column(nullable = false)
    private boolean system;

    @Column(nullable = false)
    private boolean internal;

    @Column(name = "created_at_gitlab")
    private Instant createdAtGitlab;

    @Column(name = "updated_at_gitlab")
    private Instant updatedAtGitlab;
}
