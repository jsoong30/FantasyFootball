package com.firstember.fantasyfootball.sleeper;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class SleeperRosterDTO {
    @JsonProperty("roster_id") private Integer rosterId;
    @JsonProperty("owner_id")  private String ownerId;
    @JsonProperty("players")   private List<String> players;   // Sleeper player ids, may be null pre-draft
    @JsonProperty("starters")  private List<String> starters;  // "0" = empty slot
    @JsonProperty("settings")  private Map<String, Object> settings; // wins/losses/ties live here

    public Integer getRosterId()      { return rosterId; }
    public String getOwnerId()        { return ownerId; }
    public List<String> getPlayers()  { return players; }
    public List<String> getStarters() { return starters; }

    public Integer getSettingInt(String key) {
        Object v = settings != null ? settings.get(key) : null;
        return v instanceof Number ? ((Number) v).intValue() : null;
    }
}
