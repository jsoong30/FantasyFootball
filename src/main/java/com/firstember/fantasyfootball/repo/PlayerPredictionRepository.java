package com.firstember.fantasyfootball.repo;

import com.firstember.fantasyfootball.domain.PlayerPrediction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface PlayerPredictionRepository extends JpaRepository<PlayerPrediction, Long> {

    List<PlayerPrediction> findByPredictedSeason(Integer season);

    /**
     * Same as findByPredictedSeason but eager-fetches player (and player.team) in one query.
     * Use this anywhere that touches every row's player, like the draft board's suggestion list
     * (up to 150 rows/poll, every 4s) — without the join, each row's lazy player.getTeam() call
     * is its own round-trip, and this endpoint gets hit constantly during a live draft.
     */
    @Query("SELECT pp FROM PlayerPrediction pp JOIN FETCH pp.player p LEFT JOIN FETCH p.team WHERE pp.predictedSeason = :season")
    List<PlayerPrediction> findByPredictedSeasonWithPlayer(Integer season);

    Optional<PlayerPrediction> findByPlayer_IdAndPredictedSeason(Long playerId, Integer season);

    boolean existsByPredictedSeason(Integer season);
}
