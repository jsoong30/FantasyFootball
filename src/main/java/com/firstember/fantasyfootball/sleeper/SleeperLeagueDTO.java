package com.firstember.fantasyfootball.sleeper;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class SleeperLeagueDTO {
    @JsonProperty("league_id")    private String leagueId;
    @JsonProperty("name")         private String name;
    @JsonProperty("season")       private String season;   // Sleeper sends this as a string
    @JsonProperty("status")       private String status;
    @JsonProperty("total_rosters") private Integer totalRosters;
    @JsonProperty("draft_id")     private String draftId;

    public String getLeagueId()      { return leagueId; }
    public String getName()          { return name; }
    public String getSeason()        { return season; }
    public String getStatus()        { return status; }
    public Integer getTotalRosters() { return totalRosters; }
    public String getDraftId()       { return draftId; }
}
