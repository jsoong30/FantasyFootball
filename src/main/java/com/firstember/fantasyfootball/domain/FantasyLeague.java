package com.firstember.fantasyfootball.domain;

import jakarta.persistence.*;

import java.util.ArrayList;
import java.util.List;

/**
 * One row per synced Sleeper fantasy league, private to the {@link User} who added it.
 * <p>
 * Deliberately NOT shared across users, even if two people are in the same real Sleeper league
 * and both add it here -- each gets their own independent row (and their own re-syncs). This
 * keeps the data model simple (no membership/permission checks needed for sync/delete) and
 * matches the product decision that this is a personal tool per account, not a shared team hub.
 */
@Entity
@Table(name = "fantasy_leagues",
        uniqueConstraints = @UniqueConstraint(name = "uq_league_per_owner",
                columnNames = {"sleeper_league_id", "owner_id"}))
public class FantasyLeague {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Column(name = "sleeper_league_id", nullable = false)
    private String sleeperLeagueId;

    private String name;

    private Integer season;

    /** pre_draft / drafting / in_season / complete */
    private String status;

    @Column(name = "total_rosters")
    private Integer totalRosters;

    /** Needed later to pull live picks for the draft assistant. */
    @Column(name = "sleeper_draft_id")
    private String sleeperDraftId;

    @OneToMany(mappedBy = "league", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<FantasyTeam> teams = new ArrayList<>();

    public FantasyLeague() {}

    public Long getId() { return id; }

    public User getOwner() { return owner; }
    public void setOwner(User owner) { this.owner = owner; }

    public String getSleeperLeagueId() { return sleeperLeagueId; }
    public void setSleeperLeagueId(String sleeperLeagueId) { this.sleeperLeagueId = sleeperLeagueId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Integer getSeason() { return season; }
    public void setSeason(Integer season) { this.season = season; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Integer getTotalRosters() { return totalRosters; }
    public void setTotalRosters(Integer totalRosters) { this.totalRosters = totalRosters; }

    public String getSleeperDraftId() { return sleeperDraftId; }
    public void setSleeperDraftId(String sleeperDraftId) { this.sleeperDraftId = sleeperDraftId; }

    public List<FantasyTeam> getTeams() { return teams; }
    public void setTeams(List<FantasyTeam> teams) { this.teams = teams; }
}
