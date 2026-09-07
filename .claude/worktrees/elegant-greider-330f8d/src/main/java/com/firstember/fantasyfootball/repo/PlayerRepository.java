package com.firstember.fantasyfootball.repo;

import com.firstember.fantasyfootball.domain.Player;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface PlayerRepository extends JpaRepository<Player, Long> {

    List<Player> findAllByOrderByFullNameAsc();

    List<Player> findByPositionOrderByFullNameAsc(String position);

    List<Player> findByTeam_CodeOrderByFullNameAsc(String teamCode);
}
