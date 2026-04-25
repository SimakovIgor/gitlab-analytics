package io.simakov.analytics.domain.repository;

import io.simakov.analytics.domain.model.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByGithubId(Long githubId);

    Optional<AppUser> findByEmail(String email);

    Optional<AppUser> findByEmailVerificationToken(String token);
}
