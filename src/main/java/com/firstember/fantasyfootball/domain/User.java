package com.firstember.fantasyfootball.domain;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * An app account. Deliberately separate from "Sleeper identity" -- Sleeper has no login/OAuth
 * for third-party apps (it's a read-only public API), so we can't authenticate *as* a Sleeper
 * user. Instead each account holds a real password and, once linked, a cached Sleeper user id
 * used to resolve "is this your team" within that user's own leagues (see LeagueController).
 * <p>
 * Starting as an invite-free trusted-group signup (see SecurityConfig) but built so opening
 * registration to the public later doesn't need a data model change -- just a policy change
 * (e.g. requiring email verification before {@code enabled=true}).
 */
@Entity
@Table(name = "app_users")
public class User {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    /** The Sleeper account this user has linked, if any -- set from /profile. */
    @Column(name = "sleeper_username")
    private String sleeperUsername;

    /**
     * Resolved once (via Sleeper's /user/{username} endpoint) when sleeperUsername is saved,
     * rather than re-fetched from Sleeper on every page view. This is what actually gets
     * compared against a FantasyTeam's ownerUserId to resolve "is this your team."
     */
    @Column(name = "sleeper_user_id")
    private String sleeperUserId;

    /** Reserved for the public-rollout phase (e.g. gate on email verification). Open for now. */
    @Column(nullable = false)
    private boolean enabled = true;

    /** ADMIN can reach /admin/**. No self-service promotion -- set directly in the DB. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role = Role.USER;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    public User() {}

    public Long getId() { return id; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public String getSleeperUsername() { return sleeperUsername; }
    public void setSleeperUsername(String sleeperUsername) { this.sleeperUsername = sleeperUsername; }

    public String getSleeperUserId() { return sleeperUserId; }
    public void setSleeperUserId(String sleeperUserId) { this.sleeperUserId = sleeperUserId; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
