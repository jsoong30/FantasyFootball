package com.firstember.fantasyfootball.sleeper;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * GET /state/nfl -- Sleeper's own "what point in the season is it right now" signal (keyless,
 * live). {@code season} tracks the *league* season Sleeper is currently running (e.g. during the
 * offseason before a new season's first games, this still reports last season until Sleeper rolls
 * it over) and can lag the calendar year by design -- treat it as "what week should jobs/UI
 * default to," not as a replacement for {@link com.firstember.fantasyfootball.config.SeasonConfig}
 * (which controls what the ML model projects/consumes).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SleeperNflStateDTO {
    @JsonProperty("season")        private String season;
    @JsonProperty("season_type")   private String seasonType;   // pre / regular / post
    @JsonProperty("week")          private Integer week;
    @JsonProperty("display_week")  private Integer displayWeek;

    public String getSeason()      { return season; }
    public String getSeasonType()  { return seasonType; }
    public Integer getWeek()       { return week; }
    public Integer getDisplayWeek(){ return displayWeek; }
}
