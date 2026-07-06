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

    public PlayerPrediction() {}

    public Long getId() { return id; }

    public Player getPlayer() { return player; }
    public void setPlayer(Player player) { this.player = player; }

    public Integer getPredictedSeason() { return predictedSeason; }
    public void setPredictedSeason(Integer predictedSeason) { this.predictedSeason = predictedSeason; }

    public Double getProjectedPoints() { return projectedPoints; }
    public void setProjectedPoints(Double projectedPoints) { this.projectedPoints = projectedPoints; }
}
