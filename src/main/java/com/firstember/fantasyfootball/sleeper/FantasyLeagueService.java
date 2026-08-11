package com.firstember.fantasyfootball.sleeper;

import com.firstember.fantasyfootball.domain.FantasyLeague;
import com.firstember.fantasyfootball.domain.FantasyRosterPlayer;
import com.firstember.fantasyfootball.domain.FantasyTeam;
import com.firstember.fantasyfootball.domain.User;
import com.firstember.fantasyfootball.repo.FantasyLeagueRepository;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Syncs Sleeper fantasy leagues by id, private to whichever {@link User} added them -- teams/
 * owners/records and, once a league leaves pre_draft, rosters. Separate from
 * {@link SleeperService}, which syncs league-wide NFL player stats -- this is "a league's
 * teams," not "the NFL's box scores."
 * <p>
 * If two different app users are in the same real Sleeper league and both add it, each gets
 * their own independent {@link FantasyLeague} row -- see that class's javadoc for why.
 */
@Service
public class FantasyLeagueService {

    private static final Logger log = LoggerFactory.getLogger(FantasyLeagueService.class);
    private static final String BASE = "https://api.sleeper.app/v1";

    private final RestTemplate restTemplate;
    private final FantasyLeagueRepository leagueRepository;
    private final FantasyTeamRepository teamRepository;

    public FantasyLeagueService(RestTemplateBuilder builder,
                                FantasyLeagueRepository leagueRepository,
                                FantasyTeamRepository teamRepository) {
        this.restTemplate = builder
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(20))
                .build();
        this.leagueRepository = leagueRepository;
        this.teamRepository = teamRepository;
    }

    @Transactional
    public String syncLeague(String sleeperLeagueId, User owner) {
        if (sleeperLeagueId == null || sleeperLeagueId.isBlank()) {
            return "No league id given.";
        }

        SleeperLeagueDTO leagueDto = getForObject(BASE + "/league/" + sleeperLeagueId, SleeperLeagueDTO.class);
        if (leagueDto == null || leagueDto.getName() == null) {
            return "Sleeper returned no data for league " + sleeperLeagueId + " -- check the league ID.";
        }

        FantasyLeague league = leagueRepository
                .findBySleeperLeagueIdAndOwner_Id(sleeperLeagueId, owner.getId())
                .orElse(new FantasyLeague());
        league.setOwner(owner);
        league.setSleeperLeagueId(sleeperLeagueId);
        league.setName(leagueDto.getName());
        league.setStatus(leagueDto.getStatus());
        league.setTotalRosters(leagueDto.getTotalRosters());
        league.setSleeperDraftId(leagueDto.getDraftId());
        try {
            league.setSeason(leagueDto.getSeason() != null ? Integer.parseInt(leagueDto.getSeason()) : null);
        } catch (NumberFormatException ignored) {
            // leave season null rather than fail the whole sync over an unexpected format
        }
        league = leagueRepository.save(league);

        List<SleeperLeagueUserDTO> users = getList(
                BASE + "/league/" + sleeperLeagueId + "/users",
                new ParameterizedTypeReference<List<SleeperLeagueUserDTO>>() {});
        List<SleeperRosterDTO> rosters = getList(
                BASE + "/league/" + sleeperLeagueId + "/rosters",
                new ParameterizedTypeReference<List<SleeperRosterDTO>>() {});

        Map<String, SleeperLeagueUserDTO> usersByOwnerId = new HashMap<>();
        for (SleeperLeagueUserDTO u : users) usersByOwnerId.put(u.getUserId(), u);

        int teamsSaved = 0, playersSaved = 0;
        for (SleeperRosterDTO r : rosters) {
            if (r.getRosterId() == null) continue;
            SleeperLeagueUserDTO rosterOwner = usersByOwnerId.get(r.getOwnerId());

            FantasyTeam team = teamRepository
                    .findByLeague_IdAndSleeperRosterId(league.getId(), r.getRosterId())
                    .orElse(new FantasyTeam());
            team.setLeague(league);
            team.setSleeperRosterId(r.getRosterId());
            team.setOwnerUserId(r.getOwnerId());
            team.setOwnerDisplayName(rosterOwner != null ? rosterOwner.getDisplayName() : null);
            team.setTeamName(rosterOwner != null ? rosterOwner.getTeamName() : null);
            team.setDivision(r.getSettingInt("division"));
            team.setWins(r.getSettingInt("wins"));
            team.setLosses(r.getSettingInt("losses"));
            team.setTies(r.getSettingInt("ties"));

            team.getRosterPlayers().clear();
            if (r.getPlayers() != null) {
                for (String sleeperPlayerId : r.getPlayers()) {
                    FantasyRosterPlayer rp = new FantasyRosterPlayer();
                    rp.setFantasyTeam(team);
                    rp.setSleeperPlayerId(sleeperPlayerId);
                    rp.setStarter(r.getStarters() != null && r.getStarters().contains(sleeperPlayerId));
                    team.getRosterPlayers().add(rp);
                    playersSaved++;
                }
            }

            teamRepository.save(team);
            teamsSaved++;
        }

        return String.format(
                "League \"%s\" synced -- %d teams, %d rostered players (status: %s).",
                league.getName(), teamsSaved, playersSaved, league.getStatus());
    }

    /**
     * Deletes a synced league and everything under it (teams, rostered players) -- scoped to
     * ownerId so one user can never delete another's league, even if they guess/know the id.
     */
    @Transactional
    public void deleteLeague(Long leagueId, Long ownerId) {
        leagueRepository.findByIdAndOwner_Id(leagueId, ownerId).ifPresent(leagueRepository::delete);
    }

    /**
     * Resolves a Sleeper username to its user id, e.g. when a user links their Sleeper account
     * from /profile. The id (not the username) is what's actually cached and compared against
     * FantasyTeam.ownerUserId later, since Sleeper display names aren't guaranteed unique/stable
     * the way the numeric id is. Returns null if the username doesn't exist or Sleeper is unreachable.
     */
    public String resolveSleeperUserId(String username) {
        if (username == null || username.isBlank()) return null;
        try {
            Map<?, ?> user = restTemplate.getForObject(BASE + "/user/" + username.trim(), Map.class);
            Object id = user != null ? user.get("user_id") : null;
            return id != null ? id.toString() : null;
        } catch (Exception e) {
            log.warn("Could not resolve Sleeper user id for username {}: {}", username, e.getMessage());
            return null;
        }
    }

    private <T> T getForObject(String url, Class<T> type) {
        try {
            return restTemplate.getForObject(url, type);
        } catch (Exception e) {
            log.warn("Sleeper request failed for {}: {}", url, e.getMessage());
            return null;
        }
    }

    private <T> List<T> getList(String url, ParameterizedTypeReference<List<T>> type) {
        try {
            List<T> body = restTemplate.exchange(url, HttpMethod.GET, null, type).getBody();
            return body != null ? body : List.of();
        } catch (Exception e) {
            log.warn("Sleeper request failed for {}: {}", url, e.getMessage());
            return List.of();
        }
    }
}
