package com.firstember.fantasyfootball.repo;

import com.firstember.fantasyfootball.domain.FantasyLeague;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FantasyLeagueRepository extends JpaRepository<FantasyLeague, Long> {
    Optional<FantasyLeague> findBySleeperLeagueIdAndOwner_Id(String sleeperLeagueId, Long ownerId);

    /** Every league belongs to exactly one user -- this is the only way leagues get listed. */
    List<FantasyLeague> findByOwner_Id(Long ownerId);

    /** Ownership-scoped lookup for detail/sync/delete -- returns empty for another user's league. */
    Optional<FantasyLeague> findByIdAndOwner_Id(Long id, Long ownerId);

    /** Used by the draft board to detect "does this draft belong to one of my synced leagues." */
    Optional<FantasyLeague> findBySleeperDraftIdAndOwner_Id(String sleeperDraftId, Long ownerId);
}
