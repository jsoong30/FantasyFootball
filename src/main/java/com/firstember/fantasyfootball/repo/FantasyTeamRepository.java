package com.firstember.fantasyfootball.repo;

import com.firstember.fantasyfootball.domain.FantasyTeam;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FantasyTeamRepository extends JpaRepository<FantasyTeam, Long> {
    List<FantasyTeam> findByLeague_IdOrderBySleeperRosterIdAsc(Long leagueId);
    Optional<FantasyTeam> findByLeague_IdAndSleeperRosterId(Long leagueId, Integer sleeperRosterId);
}
