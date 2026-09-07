package com.firstember.fantasyfootball.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "player_stats",
        uniqueConstraints = @UniqueConstraint(name = "uq_player_stat_season", columnNames = {"player_id", "season"}))
public class PlayerStat {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "player_id", nullable = false)
    private Player player;

    private Integer season;
    private Integer rank;

    @Column(name = "total_points")
    private Double totalPoints;

    // Passing
    @Column(name = "passing_yds")  private Integer passingYds;
    @Column(name = "passing_td")   private Integer passingTd;
    @Column(name = "passing_int")  private Integer passingInt;

    // Rushing
    @Column(name = "rushing_yds")  private Integer rushingYds;
    @Column(name = "rushing_td")   private Integer rushingTd;

    // Receiving
    @Column(name = "receiving_rec") private Integer receivingRec;
    @Column(name = "receiving_yds") private Integer receivingYds;
    @Column(name = "receiving_td")  private Integer receivingTd;

    private Integer targets;
    private Integer fumbles;

    @Column(name = "reception_pct") private Double receptionPct;

    // Kicker
    @Column(name = "pat_made")      private Integer patMade;
    @Column(name = "pat_missed")    private Integer patMissed;
    @Column(name = "fg_made_0_19")  private Integer fgMade0_19;
    @Column(name = "fg_made_20_29") private Integer fgMade20_29;
    @Column(name = "fg_made_30_39") private Integer fgMade30_39;
    @Column(name = "fg_made_40_49") private Integer fgMade40_49;
    @Column(name = "fg_made_50")    private Integer fgMade50;
    @Column(name = "fg_miss_20_29") private Integer fgMiss20_29;
    @Column(name = "fg_miss_30_39") private Integer fgMiss30_39;

    public PlayerStat() {}

    // Convenience: total FG made for kickers
    public Integer totalFgMade() {
        int total = 0;
        if (fgMade0_19  != null) total += fgMade0_19;
        if (fgMade20_29 != null) total += fgMade20_29;
        if (fgMade30_39 != null) total += fgMade30_39;
        if (fgMade40_49 != null) total += fgMade40_49;
        if (fgMade50    != null) total += fgMade50;
        return total;
    }

    // getters & setters
    public Long getId() { return id; }

    public Player getPlayer() { return player; }
    public void setPlayer(Player player) { this.player = player; }

    public Integer getSeason() { return season; }
    public void setSeason(Integer season) { this.season = season; }

    public Integer getRank() { return rank; }
    public void setRank(Integer rank) { this.rank = rank; }

    public Double getTotalPoints() { return totalPoints; }
    public void setTotalPoints(Double totalPoints) { this.totalPoints = totalPoints; }

    public Integer getPassingYds() { return passingYds; }
    public void setPassingYds(Integer passingYds) { this.passingYds = passingYds; }

    public Integer getPassingTd() { return passingTd; }
    public void setPassingTd(Integer passingTd) { this.passingTd = passingTd; }

    public Integer getPassingInt() { return passingInt; }
    public void setPassingInt(Integer passingInt) { this.passingInt = passingInt; }

    public Integer getRushingYds() { return rushingYds; }
    public void setRushingYds(Integer rushingYds) { this.rushingYds = rushingYds; }

    public Integer getRushingTd() { return rushingTd; }
    public void setRushingTd(Integer rushingTd) { this.rushingTd = rushingTd; }

    public Integer getReceivingRec() { return receivingRec; }
    public void setReceivingRec(Integer receivingRec) { this.receivingRec = receivingRec; }

    public Integer getReceivingYds() { return receivingYds; }
    public void setReceivingYds(Integer receivingYds) { this.receivingYds = receivingYds; }

    public Integer getReceivingTd() { return receivingTd; }
    public void setReceivingTd(Integer receivingTd) { this.receivingTd = receivingTd; }

    public Integer getTargets() { return targets; }
    public void setTargets(Integer targets) { this.targets = targets; }

    public Integer getFumbles() { return fumbles; }
    public void setFumbles(Integer fumbles) { this.fumbles = fumbles; }

    public Double getReceptionPct() { return receptionPct; }
    public void setReceptionPct(Double receptionPct) { this.receptionPct = receptionPct; }

    public Integer getPatMade() { return patMade; }
    public void setPatMade(Integer patMade) { this.patMade = patMade; }

    public Integer getPatMissed() { return patMissed; }
    public void setPatMissed(Integer patMissed) { this.patMissed = patMissed; }

    public Integer getFgMade0_19() { return fgMade0_19; }
    public void setFgMade0_19(Integer fgMade0_19) { this.fgMade0_19 = fgMade0_19; }

    public Integer getFgMade20_29() { return fgMade20_29; }
    public void setFgMade20_29(Integer fgMade20_29) { this.fgMade20_29 = fgMade20_29; }

    public Integer getFgMade30_39() { return fgMade30_39; }
    public void setFgMade30_39(Integer fgMade30_39) { this.fgMade30_39 = fgMade30_39; }

    public Integer getFgMade40_49() { return fgMade40_49; }
    public void setFgMade40_49(Integer fgMade40_49) { this.fgMade40_49 = fgMade40_49; }

    public Integer getFgMade50() { return fgMade50; }
    public void setFgMade50(Integer fgMade50) { this.fgMade50 = fgMade50; }

    public Integer getFgMiss20_29() { return fgMiss20_29; }
    public void setFgMiss20_29(Integer fgMiss20_29) { this.fgMiss20_29 = fgMiss20_29; }

    public Integer getFgMiss30_39() { return fgMiss30_39; }
    public void setFgMiss30_39(Integer fgMiss30_39) { this.fgMiss30_39 = fgMiss30_39; }
}
