package com.firstember.fantasyfootball.repo;

import com.firstember.fantasyfootball.domain.Team;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface TeamRepository extends JpaRepository<Team, Long> {
    List<Team> findAllByOrderByCodeAsc();

    // Excludes the "FA" (Free Agent) placeholder team used to associate
    // teamless players — it isn't a real NFL team and shouldn't appear
    // in team listings or counts.
    List<Team> findByCodeNotOrderByCodeAsc(String code);
    long countByCodeNot(String code);
}
