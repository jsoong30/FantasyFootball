package com.firstember.fantasyfootball.domain;

import jakarta.persistence.*;

/**
 * One drafted/rostered player on a {@link FantasyTeam}.
 * <p>
 * Stores the raw Sleeper player id rather than a hard FK to {@link Player} -- Player rows are
 * season-indexed (one per player per stat season), but a fantasy roster is a "right now" concept
 * with no season of its own. Resolving to a name/position/team happens at read time by matching
 * this id against {@code Player.externalId} for whatever the latest synced season is.
 */
@Entity
@Table(name = "fantasy_roster_players",
        uniqueConstraints = @UniqueConstraint(name = "uq_roster_player",
                columnNames = {"fantasy_team_id", "sleeper_player_id"}))
public class FantasyRosterPlayer {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "fantasy_team_id", nullable = false)
    private FantasyTeam fantasyTeam;

    @Column(name = "sleeper_player_id", nullable = false)
    private String sleeperPlayerId;

    /** True if Sleeper currently lists this player in the roster's starting lineup vs. bench. */
    @Column(name = "is_starter")
    private boolean isStarter;

    public FantasyRosterPlayer() {}

    public Long getId() { return id; }

    public FantasyTeam getFantasyTeam() { return fantasyTeam; }
    public void setFantasyTeam(FantasyTeam fantasyTeam) { this.fantasyTeam = fantasyTeam; }

    public String getSleeperPlayerId() { return sleeperPlayerId; }
    public void setSleeperPlayerId(String sleeperPlayerId) { this.sleeperPlayerId = sleeperPlayerId; }

    public boolean isStarter() { return isStarter; }
    public void setStarter(boolean starter) { isStarter = starter; }
}
