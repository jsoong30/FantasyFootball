package com.firstember.fantasyfootball.domain;

import jakarta.persistence.*;

import java.util.ArrayList;
import java.util.List;

/** One row per Sleeper roster slot (team) in a synced {@link FantasyLeague}. */
@Entity
@Table(name = "fantasy_teams",
        uniqueConstraints = @UniqueConstraint(name = "uq_fantasy_team",
                columnNames = {"league_id", "sleeper_roster_id"}))
public class FantasyTeam {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "league_id", nullable = false)
    private FantasyLeague league;

    @Column(name = "sleeper_roster_id", nullable = false)
    private Integer sleeperRosterId;

    @Column(name = "owner_user_id")
    private String ownerUserId;

    @Column(name = "owner_display_name")
    private String ownerDisplayName;

    /** Custom team name if the owner set one, otherwise falls back to their display name. */
    @Column(name = "team_name")
    private String teamName;

    private Integer division;

    private Integer wins;
    private Integer losses;
    private Integer ties;

    @OneToMany(mappedBy = "fantasyTeam", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<FantasyRosterPlayer> rosterPlayers = new ArrayList<>();

    public FantasyTeam() {}

    public Long getId() { return id; }

    public FantasyLeague getLeague() { return league; }
    public void setLeague(FantasyLeague league) { this.league = league; }

    public Integer getSleeperRosterId() { return sleeperRosterId; }
    public void setSleeperRosterId(Integer sleeperRosterId) { this.sleeperRosterId = sleeperRosterId; }

    public String getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(String ownerUserId) { this.ownerUserId = ownerUserId; }

    public String getOwnerDisplayName() { return ownerDisplayName; }
    public void setOwnerDisplayName(String ownerDisplayName) { this.ownerDisplayName = ownerDisplayName; }

    public String getTeamName() { return teamName; }
    public void setTeamName(String teamName) { this.teamName = teamName; }

    public Integer getDivision() { return division; }
    public void setDivision(Integer division) { this.division = division; }

    public Integer getWins() { return wins; }
    public void setWins(Integer wins) { this.wins = wins; }

    public Integer getLosses() { return losses; }
    public void setLosses(Integer losses) { this.losses = losses; }

    public Integer getTies() { return ties; }
    public void setTies(Integer ties) { this.ties = ties; }

    public List<FantasyRosterPlayer> getRosterPlayers() { return rosterPlayers; }
    public void setRosterPlayers(List<FantasyRosterPlayer> rosterPlayers) { this.rosterPlayers = rosterPlayers; }

    /** Display name shown in the UI — custom team name if set, otherwise the owner's Sleeper handle. */
    public String getDisplayLabel() {
        return teamName != null && !teamName.isBlank() ? teamName : ownerDisplayName;
    }
}
