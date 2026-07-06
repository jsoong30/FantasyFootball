package com.firstember.fantasyfootball.repo;

import com.firstember.fantasyfootball.domain.PlayerPrediction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PlayerPredictionRepository extends JpaRepository<PlayerPrediction, Long> {

    List<PlayerPrediction> findByPredictedSeason(Integer season);

    Optional<PlayerPrediction> findByPlayer_IdAndPredictedSeason(Long playerId, Integer season);
}
