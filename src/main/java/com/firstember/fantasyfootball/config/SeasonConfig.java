package com.firstember.fantasyfootball.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Single place to roll the app's "current" seasons when a new NFL season starts, instead of the
 * {@code TARGET_SEASON}/{@code SOURCE_SEASON} constants that used to be copy-pasted across
 * {@code PredictionsController}, {@code HomeController}, and {@code DraftController} (three
 * places to remember to update in lockstep, easy to drift). Backed by {@code app.season.*} in
 * application.yml so it's also an env-var override ({@code SOURCE_SEASON}/{@code TARGET_SEASON})
 * without a code change.
 * <p>
 * {@code targetSeason} = the season being projected (what the ML model outputs, what a draft
 * happening now is drafting for). {@code sourceSeason} = the most recently completed season, used
 * as the model's input stats. See CLAUDE.md ("Why SOURCE_SEASON=2025, TARGET_SEASON=2026").
 */
@Component
public class SeasonConfig {

    @Value("${app.season.source-season:2025}")
    private int sourceSeason;

    @Value("${app.season.target-season:2026}")
    private int targetSeason;

    public int getSourceSeason() {
        return sourceSeason;
    }

    public int getTargetSeason() {
        return targetSeason;
    }
}
