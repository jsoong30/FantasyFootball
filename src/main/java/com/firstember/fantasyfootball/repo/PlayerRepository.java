package com.firstember.fantasyfootball.repo;

import com.firstember.fantasyfootball.domain.Player;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.Optional;

public interface PlayerRepository extends JpaRepository<Player, Long> {

    List<Player> findAllByOrderByFullNameAsc();

    List<Player> findByPositionOrderByFullNameAsc(String position);

    List<Player> findByTeam_CodeOrderByFullNameAsc(String teamCode);

    // ── Season-aware queries ──────────────────────────────────────────────────

    /** All players for a specific season (controller handles sorting). */
    List<Player> findBySeason(Integer season);

    /** Team roster for a specific season. */
    List<Player> findByTeam_CodeAndSeason(String teamCode, Integer season);

    /** All distinct seasons that have player data, newest first. */
    @Query("SELECT DISTINCT p.season FROM Player p WHERE p.season IS NOT NULL ORDER BY p.season DESC")
    List<Integer> findDistinctSeasons();

    // ── Sleeper sync lookups ──────────────────────────────────────────────────

    /** Look up a player by their Sleeper ID for a specific season. */
    Optional<Player> findByExternalIdAndSeason(String externalId, Integer season);

    /**
     * Fallback: match by name + position + season when externalId may not be set yet
     * (e.g. existing CSV-loaded players). Returns the first match.
     */
    Optional<Player> findFirstByFullNameAndPositionAndSeason(String fullName, String position, Integer season);

    /**
     * Resolve a Sleeper player id to whatever the most recently synced season's row is.
     * Used for "current" concepts that aren't season-indexed themselves (e.g. fantasy
     * league rosters) -- there's no single "right" season for a live roster, so this
     * just takes the newest data we have.
     */
    Optional<Player> findFirstByExternalIdOrderBySeasonDesc(String externalId);
}
