package com.firstember.fantasyfootball.sleeper;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class SleeperLeagueUserDTO {
    @JsonProperty("user_id")      private String userId;
    @JsonProperty("display_name") private String displayName;
    @JsonProperty("metadata")     private Map<String, Object> metadata;

    public String getUserId()      { return userId; }
    public String getDisplayName() { return displayName; }

    /** Custom team name the owner set for their roster, or null if they never set one. */
    public String getTeamName() {
        Object v = metadata != null ? metadata.get("team_name") : null;
        return v != null ? v.toString() : null;
    }
}
