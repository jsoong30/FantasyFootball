package com.firstember.fantasyfootball.sleeper;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class SleeperDraftDTO {
    @JsonProperty("draft_id")   private String draftId;
    @JsonProperty("league_id")  private String leagueId;   // null for a standalone mock draft
    @JsonProperty("status")     private String status;      // pre_draft / drafting / complete
    @JsonProperty("type")       private String type;        // snake / linear / auction
    @JsonProperty("settings")   private Map<String, Object> settings;   // rounds, teams live here
    @JsonProperty("slot_to_roster_id") private Map<String, Integer> slotToRosterId; // may be null pre-draft
    @JsonProperty("draft_order") private Map<String, Integer> draftOrder; // user_id -> slot number, null for some mock drafts

    public String getDraftId()  { return draftId; }
    public String getLeagueId() { return leagueId; }
    public String getStatus()   { return status; }
    public String getType()     { return type; }
    public Map<String, Integer> getDraftOrder() { return draftOrder; }
    public Map<String, Integer> getSlotToRosterId() { return slotToRosterId; }

    public Integer getRounds() {
        Object v = settings != null ? settings.get("rounds") : null;
        return v instanceof Number ? ((Number) v).intValue() : null;
    }

    public Integer getTeams() {
        Object v = settings != null ? settings.get("teams") : null;
        return v instanceof Number ? ((Number) v).intValue() : null;
    }
}
