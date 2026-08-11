package com.firstember.fantasyfootball.repo;

import com.firstember.fantasyfootball.domain.FantasyRosterPlayer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FantasyRosterPlayerRepository extends JpaRepository<FantasyRosterPlayer, Long> {
    List<FantasyRosterPlayer> findByFantasyTeam_Id(Long fantasyTeamId);

    /** Which fantasy team (if any) currently rosters this Sleeper player -- for the draft assistant. */
    Optional<FantasyRosterPlayer> findByFantasyTeam_League_IdAndSleeperPlayerId(Long leagueId, String sleeperPlayerId);
}
