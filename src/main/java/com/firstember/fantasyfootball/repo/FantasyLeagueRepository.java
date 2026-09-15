package com.firstember.fantasyfootball.repo;

import com.firstember.fantasyfootball.domain.FantasyLeague;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface FantasyLeagueRepository extends JpaRepository<FantasyLeague, Long> {
    Optional<FantasyLeague> findBySleeperLeagueIdAndOwner_Id(String sleeperLeagueId, Long ownerId);

    /** Every league belongs to exactly one user -- this is the only way leagues get listed. */
    List<FantasyLeague> findByOwner_Id(Long ownerId);

    /**
     * Every league, with {@code owner} eagerly fetched. Used by the background "sync all leagues"
     * job (see FantasyLeagueService.syncAllLeagues), which runs on a scheduler thread with no
     * open web request -- Open-Session-In-View only covers HTTP requests, so a plain findAll()
     * here would throw LazyInitializationException the moment the loop touches league.getOwner().
     */
    @Query("SELECT l FROM FantasyLeague l JOIN FETCH l.owner")
    List<FantasyLeague> findAllWithOwner();

    /** Ownership-scoped lookup for detail/sync/delete -- returns empty for another user's league. */
    Optional<FantasyLeague> findByIdAndOwner_Id(Long id, Long ownerId);

    /** Used by the draft board to detect "does this draft belong to one of my synced leagues." */
    Optional<FantasyLeague> findBySleeperDraftIdAndOwner_Id(String sleeperDraftId, Long ownerId);
}
