package com.firstember.fantasyfootball.repo;

import com.firstember.fantasyfootball.domain.PlayerWeeklyStat;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PlayerWeeklyStatRepository extends JpaRepository<PlayerWeeklyStat, Long> {

    /** All weekly records for a player in a given season, ordered by week. */
    List<PlayerWeeklyStat> findByPlayer_IdAndSeasonOrderByWeekAsc(Long playerId, Integer season);

    /** Single week lookup for upsert. */
    Optional<PlayerWeeklyStat> findByPlayer_IdAndSeasonAndWeek(Long playerId, Integer season, Integer week);

    /** Batch load all weekly records for a set of players in a given season (used during sync). */
    List<PlayerWeeklyStat> findByPlayer_IdInAndSeason(Collection<Long> playerIds, Integer season);
}
