package com.firstember.fantasyfootball.sleeper;

import com.firstember.fantasyfootball.domain.FantasyLeague;
import com.firstember.fantasyfootball.domain.FantasyRosterPlayer;
import com.firstember.fantasyfootball.domain.FantasyTeam;
import com.firstember.fantasyfootball.repo.FantasyRosterPlayerRepository;
import com.firstember.fantasyfootball.repo.FantasyTeamRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.List;

/**
 * Live draft tracking -- pulls a Sleeper draft's picks (polled by the frontend every few
 * seconds, see DraftController) and, when the draft belongs to one of the user's synced
 * leagues, persists newly-seen picks straight into that league's rosters so the regular
 * /league/{id} view reflects the draft as it happens, not just after a manual re-sync.
 * <p>
 * Works against ANY Sleeper draft id, including a standalone practice "mock draft" with no
 * real league_id -- that's how the whole board/suggestions UI gets tested before a real draft,
 * see CLAUDE.md. A standalone draft just has nothing to persist picks into, since there's no
 * real FantasyTeam to attach them to.
 */
@Service
public class DraftService {

    private static final Logger log = LoggerFactory.getLogger(DraftService.class);
    private static final String BASE = "https://api.sleeper.app/v1";

    private final RestTemplate restTemplate;
    private final FantasyTeamRepository teamRepository;
    private final FantasyRosterPlayerRepository rosterPlayerRepository;

    public DraftService(RestTemplateBuilder builder,
                        FantasyTeamRepository teamRepository,
                        FantasyRosterPlayerRepository rosterPlayerRepository) {
        this.restTemplate = builder
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(20))
                .build();
        this.teamRepository = teamRepository;
        this.rosterPlayerRepository = rosterPlayerRepository;
    }

    public SleeperDraftDTO getDraft(String draftId) {
        try {
            return restTemplate.getForObject(BASE + "/draft/" + draftId, SleeperDraftDTO.class);
        } catch (Exception e) {
            log.warn("Could not fetch draft {}", draftId, e);
            return null;
        }
    }

    public List<SleeperDraftPickDTO> getPicks(String draftId) {
        try {
            List<SleeperDraftPickDTO> picks = restTemplate.exchange(
                    BASE + "/draft/" + draftId + "/picks", HttpMethod.GET, null,
                    new ParameterizedTypeReference<List<SleeperDraftPickDTO>>() {}).getBody();
            return picks != null ? picks : List.of();
        } catch (Exception e) {
            log.warn("Could not fetch picks for draft {}", draftId, e);
            return List.of();
        }
    }

    /**
     * Upserts newly-seen picks into the league's rosters. Idempotent -- safe to call on every
     * poll, since it checks for an existing FantasyRosterPlayer before inserting.
     */
    @Transactional
    public int syncPicksToRoster(FantasyLeague league, List<SleeperDraftPickDTO> picks) {
        int added = 0;
        for (SleeperDraftPickDTO pick : picks) {
            if (pick.getPlayerId() == null || pick.getRosterId() == null) continue; // not picked yet, or no roster to attach to

            boolean alreadyRecorded = rosterPlayerRepository
                    .findByFantasyTeam_League_IdAndSleeperPlayerId(league.getId(), pick.getPlayerId())
                    .isPresent();
            if (alreadyRecorded) continue;

            FantasyTeam team = teamRepository
                    .findByLeague_IdAndSleeperRosterId(league.getId(), pick.getRosterId())
                    .orElse(null);
            if (team == null) continue; // roster_id doesn't match a synced team -- shouldn't happen, skip defensively

            FantasyRosterPlayer rp = new FantasyRosterPlayer();
            rp.setFantasyTeam(team);
            rp.setSleeperPlayerId(pick.getPlayerId());
            rp.setStarter(false);
            team.getRosterPlayers().add(rp);
            teamRepository.save(team);
            added++;
        }
        return added;
    }
}
