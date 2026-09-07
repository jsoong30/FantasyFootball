package com.firstember.fantasyfootball.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "player_predictions",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_player_prediction",
                columnNames = {"player_id", "predicted_season"}))
public class PlayerPrediction {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "player_id", nullable = false)
    private Player player;

    @Column(name = "predicted_season", nullable = false)
    private Integer predictedSeason;

    @Column(name = "projected_points")
    private Double projectedPoints;

    // Market context snapshotted at sync time so downstream features (e.g. week-by-week
    // start/sit) can use it without re-hitting the rate-limited external APIs. All nullable —
    // a player may have no ADP / no FantasyPros entry, and K/DST never get a market blend.
    /** Our projection BEFORE serve.py's market-consensus blend (Guardrail 5). */
    @Column(name = "model_points")
    private Double modelPoints;

    /** FantasyPros consensus PPR season projection used in the blend. */
    @Column(name = "market_points")
    private Double marketPoints;

    /** Fantasy Football Calculator overall PPR ADP (lower = drafted earlier). */
    @Column(name = "market_adp")
    private Double marketAdp;

    public PlayerPrediction() {}

    public Long getId() { return id; }

    public Player getPlayer() { return player; }
    public void setPlayer(Player player) { this.player = player; }

    public Integer getPredictedSeason() { return predictedSeason; }
    public void setPredictedSeason(Integer predictedSeason) { this.predictedSeason = predictedSeason; }

    public Double getProjectedPoints() { return projectedPoints; }
    public void setProjectedPoints(Double projectedPoints) { this.projectedPoints = projectedPoints; }

    public Double getModelPoints() { return modelPoints; }
    public void setModelPoints(Double modelPoints) { this.modelPoints = modelPoints; }

    public Double getMarketPoints() { return marketPoints; }
    public void setMarketPoints(Double marketPoints) { this.marketPoints = marketPoints; }

    public Double getMarketAdp() { return marketAdp; }
    public void setMarketAdp(Double marketAdp) { this.marketAdp = marketAdp; }
}
