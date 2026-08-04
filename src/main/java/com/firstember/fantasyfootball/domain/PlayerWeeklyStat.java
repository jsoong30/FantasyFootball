package com.firstember.fantasyfootball.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "player_weekly_stats",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_player_week",
                columnNames = {"player_id", "season", "week"}))
public class PlayerWeeklyStat {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "player_id", nullable = false)
    private Player player;

    private Integer season;
    private Integer week;

    @Column(name = "total_points")  private Double  totalPoints;

    // Passing
    @Column(name = "passing_yds")   private Integer passingYds;
    @Column(name = "passing_td")    private Integer passingTd;
    @Column(name = "passing_int")   private Integer passingInt;

    // Rushing
    @Column(name = "rushing_yds")   private Integer rushingYds;
    @Column(name = "rushing_td")    private Integer rushingTd;

    // Receiving
    @Column(name = "receiving_rec") private Integer receivingRec;
    @Column(name = "receiving_yds") private Integer receivingYds;
    @Column(name = "receiving_td")  private Integer receivingTd;
    private Integer targets;
    private Integer fumbles;

    // Usage — offensive snaps this player played, and the team's total offensive snaps that week
    @Column(name = "off_snaps")      private Integer offSnaps;
    @Column(name = "team_off_snaps") private Integer teamOffSnaps;

    // Kicker
    @Column(name = "pat_made")      private Integer patMade;
    @Column(name = "pat_missed")    private Integer patMissed;
    @Column(name = "fg_made")       private Integer fgMade;   // total FG made that week

    // Defense
    @Column(name = "def_sacks")     private Integer defSacks;
    @Column(name = "def_ints")      private Integer defInts;
    @Column(name = "def_fum_rec")   private Integer defFumRecoveries;
    @Column(name = "def_td")        private Integer defTd;
    @Column(name = "def_safeties")  private Integer defSafeties;
    @Column(name = "def_blk_kick")  private Integer defBlockedKicks;
    @Column(name = "pts_allowed")   private Integer ptsAllowed;

    /** The team code of the defense this player faced that week (e.g. "KC", "SF"). Nullable — backfilled via ESPN schedule. */
    @Column(name = "opponent_code", length = 10)
    private String opponentCode;

    public PlayerWeeklyStat() {}

    // ── Getters & Setters ──────────────────────────────────────────────────

    public Long getId()                     { return id; }

    public Player getPlayer()               { return player; }
    public void setPlayer(Player player)    { this.player = player; }

    public Integer getSeason()              { return season; }
    public void setSeason(Integer season)   { this.season = season; }

    public Integer getWeek()                { return week; }
    public void setWeek(Integer week)       { this.week = week; }

    public Double getTotalPoints()                  { return totalPoints; }
    public void setTotalPoints(Double totalPoints)  { this.totalPoints = totalPoints; }

    public Integer getPassingYds()                  { return passingYds; }
    public void setPassingYds(Integer passingYds)   { this.passingYds = passingYds; }

    public Integer getPassingTd()                   { return passingTd; }
    public void setPassingTd(Integer passingTd)     { this.passingTd = passingTd; }

    public Integer getPassingInt()                  { return passingInt; }
    public void setPassingInt(Integer passingInt)   { this.passingInt = passingInt; }

    public Integer getRushingYds()                  { return rushingYds; }
    public void setRushingYds(Integer rushingYds)   { this.rushingYds = rushingYds; }

    public Integer getRushingTd()                   { return rushingTd; }
    public void setRushingTd(Integer rushingTd)     { this.rushingTd = rushingTd; }

    public Integer getReceivingRec()                { return receivingRec; }
    public void setReceivingRec(Integer v)          { this.receivingRec = v; }

    public Integer getReceivingYds()                { return receivingYds; }
    public void setReceivingYds(Integer v)          { this.receivingYds = v; }

    public Integer getReceivingTd()                 { return receivingTd; }
    public void setReceivingTd(Integer v)           { this.receivingTd = v; }

    public Integer getTargets()                     { return targets; }
    public void setTargets(Integer targets)         { this.targets = targets; }

    public Integer getFumbles()                     { return fumbles; }
    public void setFumbles(Integer fumbles)         { this.fumbles = fumbles; }

    public Integer getOffSnaps()                    { return offSnaps; }
    public void setOffSnaps(Integer offSnaps)       { this.offSnaps = offSnaps; }

    public Integer getTeamOffSnaps()                { return teamOffSnaps; }
    public void setTeamOffSnaps(Integer teamOffSnaps) { this.teamOffSnaps = teamOffSnaps; }

    public Integer getPatMade()                     { return patMade; }
    public void setPatMade(Integer patMade)         { this.patMade = patMade; }

    public Integer getPatMissed()                   { return patMissed; }
    public void setPatMissed(Integer patMissed)     { this.patMissed = patMissed; }

    public Integer getFgMade()                      { return fgMade; }
    public void setFgMade(Integer fgMade)           { this.fgMade = fgMade; }

    public Integer getDefSacks()                    { return defSacks; }
    public void setDefSacks(Integer v)              { this.defSacks = v; }

    public Integer getDefInts()                     { return defInts; }
    public void setDefInts(Integer v)               { this.defInts = v; }

    public Integer getDefFumRecoveries()            { return defFumRecoveries; }
    public void setDefFumRecoveries(Integer v)      { this.defFumRecoveries = v; }

    public Integer getDefTd()                       { return defTd; }
    public void setDefTd(Integer v)                 { this.defTd = v; }

    public Integer getDefSafeties()                 { return defSafeties; }
    public void setDefSafeties(Integer v)           { this.defSafeties = v; }

    public Integer getDefBlockedKicks()             { return defBlockedKicks; }
    public void setDefBlockedKicks(Integer v)       { this.defBlockedKicks = v; }

    public Integer getPtsAllowed()                  { return ptsAllowed; }
    public void setPtsAllowed(Integer v)            { this.ptsAllowed = v; }

    public String getOpponentCode()                 { return opponentCode; }
    public void setOpponentCode(String v)           { this.opponentCode = v; }
}
