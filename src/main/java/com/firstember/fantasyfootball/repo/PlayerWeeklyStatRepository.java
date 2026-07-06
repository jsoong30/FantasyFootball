package com.firstember.fantasyfootball.repo;

import com.firstember.fantasyfootball.domain.PlayerWeeklyStat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

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

    /** Returns [player_id, season, games_played] for all players across all seasons. */
    @Query("SELECT w.player.id, w.season, COUNT(w) FROM PlayerWeeklyStat w GROUP BY w.player.id, w.season")
    List<Object[]> countWeeksByPlayerAndSeason();

    /** Returns [player_id, games_played] for all players in a specific season. */
    @Query("SELECT w.player.id, COUNT(w) FROM PlayerWeeklyStat w WHERE w.season = :season GROUP BY w.player.id")
    List<Object[]> countWeeksByPlayerForSeason(@org.springframework.data.repository.query.Param("season") Integer season);

    /** Returns [player_id, season, total_points] for every non-null weekly row — used for consistency metrics. */
    @Query("SELECT w.player.id, w.season, w.totalPoints FROM PlayerWeeklyStat w WHERE w.totalPoints IS NOT NULL")
    List<Object[]> findAllWeeklyPoints();

    /** Returns [player_id, total_points] for a specific season — used for consistency metrics during prediction sync. */
    @Query("SELECT w.player.id, w.totalPoints FROM PlayerWeeklyStat w WHERE w.season = :season AND w.totalPoints IS NOT NULL")
    List<Object[]> findWeeklyPointsBySeason(@org.springframework.data.repository.query.Param("season") Integer season);

    /** All weekly stats for a specific season and week — used to backfill opponent_code. */
    List<PlayerWeeklyStat> findBySeasonAndWeek(Integer season, Integer week);

    /**
     * Returns [player_id, opponent_code, total_points, position] for a season — used to compute
     * schedule strength (opponent defensive rating) before calling the ML API.
     * Only includes rows where both opponent_code and total_points are present.
     */
    @Query("SELECT w.player.id, w.opponentCode, w.totalPoints, w.player.position " +
           "FROM PlayerWeeklyStat w " +
           "WHERE w.season = :season AND w.opponentCode IS NOT NULL AND w.totalPoints IS NOT NULL")
    List<Object[]> findWeeklyPtsWithOpponentForSeason(@org.springframework.data.repository.query.Param("season") Integer season);

    /**
     * Returns [season, week, full_name, position, team_code, opponent_code, total_points]
     * for every weekly row that has points — used for the weekly training data export.
     * Uses explicit LEFT JOIN so players without a team are still included (team_code = null).
     */
    @Query("SELECT w.season, w.week, p.fullName, p.position, " +
           "COALESCE(t.code, ''), w.opponentCode, w.totalPoints " +
           "FROM PlayerWeeklyStat w JOIN w.player p LEFT JOIN p.team t " +
           "WHERE w.totalPoints IS NOT NULL " +
           "ORDER BY w.season ASC, w.week ASC, p.fullName ASC")
    List<Object[]> findWeeklyExportData();
}
