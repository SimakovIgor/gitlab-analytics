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
@Table(name = "app_user")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true)
    private Long githubId;

    @Column(unique = true)
    private String githubLogin;

    private String name;
    private String avatarUrl;
    private String email;

    /** BCrypt hash. Null for GitHub-OAuth-only users. */
    @Column(name = "password_hash")
    private String passwordHash;

    /** True once the user has clicked the verification link in the welcome email. */
    @Column(nullable = false)
    private boolean emailVerified;

    /** One-time token sent in the verification email. Null after verification. */
    @Column(name = "email_verification_token")
    private String emailVerificationToken;

    @Column(name = "email_verification_expires_at")
    private Instant emailVerificationExpiresAt;

    @CreationTimestamp
    @Column(nullable = false,
            updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant lastLoginAt;
}
