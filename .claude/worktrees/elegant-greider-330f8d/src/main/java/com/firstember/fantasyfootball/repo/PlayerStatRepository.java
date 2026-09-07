package com.firstember.fantasyfootball.repo;

import com.firstember.fantasyfootball.domain.PlayerStat;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface PlayerStatRepository extends JpaRepository<PlayerStat, Long> {

    List<PlayerStat> findByPlayer_IdIn(List<Long> playerIds);

    Optional<PlayerStat> findByPlayer_IdAndSeason(Long playerId, Integer season);

    List<PlayerStat> findBySeasonOrderByRankAsc(Integer season);

    List<PlayerStat> findByPlayer_PositionAndSeasonOrderByRankAsc(String position, Integer season);
}
