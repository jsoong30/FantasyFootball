package com.firstember.fantasyfootball.sleeper;

import com.firstember.fantasyfootball.domain.FantasyLeague;
import com.firstember.fantasyfootball.repo.FantasyLeagueRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Weekly automatic refresh of every user's added leagues -- standings (wins/losses/ties),
 * scoring (points for/against), rosters, and league status. Default cron is Tuesday 7am, after
 * Sunday/Monday games (and any Thursday-night carryover) have posted final stats -- override
 * with {@code app.scheduler.league-sync-cron} if your league wants a different cadence.
 * <p>
 * The scheduled trigger is gated by {@code app.scheduler.enabled} (defaults to true) via a check
 * inside {@link #scheduledSync()}, not a conditional bean -- {@link #syncAllLeaguesNow()} is also
 * the manual "Sync All Leagues" admin button (AdminController holds a required constructor
 * dependency on this class), and that needs to keep working even with the schedule switched off.
 * <p>
 * {@link #syncAllLeaguesNow()} calls {@code fantasyLeagueService.syncLeague(...)} as a genuine
 * cross-bean call, deliberately not from a method living on {@code FantasyLeagueService} itself:
 * a self-invoked call to a {@code @Transactional} method bypasses Spring's proxy -- and with it
 * the transaction boundary -- entirely. That matters twice over here. First, isolation: one bad
 * league's JPA failure would otherwise taint a transaction shared with every other league in the
 * batch instead of just its own. Second, and the one that would actually throw: this runs on a
 * scheduler thread with no HTTP request in flight, so there's no Open-Session-In-View keeping a
 * session open across calls -- {@code syncLeague} touches lazy fields (a team's roster players),
 * which need its own live transaction to load without a {@code LazyInitializationException}.
 */
@Component
public class LeagueSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(LeagueSyncScheduler.class);

    private final FantasyLeagueRepository leagueRepository;
    private final FantasyLeagueService fantasyLeagueService;

    @Value("${app.scheduler.enabled:true}")
    private boolean schedulerEnabled;

    public LeagueSyncScheduler(FantasyLeagueRepository leagueRepository, FantasyLeagueService fantasyLeagueService) {
        this.leagueRepository = leagueRepository;
        this.fantasyLeagueService = fantasyLeagueService;
    }

    @Scheduled(cron = "${app.scheduler.league-sync-cron:0 0 7 * * TUE}")
    public void scheduledSync() {
        if (!schedulerEnabled) {
            log.debug("app.scheduler.enabled=false -- skipping scheduled league sync");
            return;
        }
        log.info("Scheduled league sync starting...");
        log.info("Scheduled league sync finished: {}", syncAllLeaguesNow());
    }

    /**
     * Re-syncs every league every user has added. Not owner-scoped (unlike the per-request
     * endpoints in LeagueController) -- this is a background job over the whole table, not a
     * user action, so there's no "current user" to scope it to. Each league is synced
     * independently: one bad one (deleted on Sleeper, a transient network blip) is logged and
     * skipped rather than aborting the batch.
     */
    public String syncAllLeaguesNow() {
        // findAllWithOwner(), not findAll(): league.getOwner() below needs the owner already
        // loaded (see class javadoc re: no Open-Session-In-View on this thread).
        List<FantasyLeague> leagues = leagueRepository.findAllWithOwner();
        int ok = 0, failed = 0;
        for (FantasyLeague league : leagues) {
            try {
                fantasyLeagueService.syncLeague(league.getSleeperLeagueId(), league.getOwner());
                ok++;
            } catch (Exception e) {
                failed++;
                log.warn("Auto-sync failed for league {} (owner {}): {}",
                        league.getSleeperLeagueId(), league.getOwner().getId(), e.getMessage());
            }
        }
        String msg = String.format("Synced %d/%d leagues (%d failed).", ok, leagues.size(), failed);
        log.info(msg);
        return msg;
    }
}
