package com.firstember.fantasyfootball.sleeper;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class SleeperDraftPickDTO {
    @JsonProperty("pick_no")    private Integer pickNo;
    @JsonProperty("round")      private Integer round;
    @JsonProperty("draft_slot") private Integer draftSlot;
    @JsonProperty("roster_id")  private Integer rosterId;   // null for a standalone mock draft
    @JsonProperty("picked_by")  private String pickedBy;    // Sleeper user id, "" if a bot autopicked
    @JsonProperty("player_id")  private String playerId;
    @JsonProperty("metadata")   private Map<String, String> metadata; // first_name/last_name/position/team

    public Integer getPickNo()    { return pickNo; }
    public Integer getRound()     { return round; }
    public Integer getDraftSlot() { return draftSlot; }
    public Integer getRosterId()  { return rosterId; }
    public String getPickedBy()   { return pickedBy; }
    public String getPlayerId()   { return playerId; }

    public String getDisplayName() {
        if (metadata == null) return null;
        String first = metadata.getOrDefault("first_name", "");
        String last = metadata.getOrDefault("last_name", "");
        String name = (first + " " + last).trim();
        return name.isEmpty() ? null : name;
    }

    public String getPosition() { return metadata != null ? metadata.get("position") : null; }
}
