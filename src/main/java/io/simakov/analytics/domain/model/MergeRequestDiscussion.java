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
@Table(name = "merge_request_discussion")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MergeRequestDiscussion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merge_request_id",
            nullable = false)
    private Long mergeRequestId;

    @Column(name = "gitlab_discussion_id",
            nullable = false,
            length = 64)
    private String gitlabDiscussionId;

    @Column(name = "individual_note",
            nullable = false)
    private boolean individualNote;

    @Column(name = "created_at_gitlab")
    private Instant createdAtGitlab;
}
