package com.firstember.fantasyfootball.sleeper;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class SleeperPlayerDTO {

    @JsonProperty("player_id")   private String playerId;
    @JsonProperty("full_name")   private String fullName;
    @JsonProperty("first_name")  private String firstName;
    @JsonProperty("last_name")   private String lastName;
    @JsonProperty("position")    private String position;
    @JsonProperty("team")        private String team;
    @JsonProperty("age")         private Integer age;
    @JsonProperty("birth_date")  private String birthDate;
    @JsonProperty("status")      private String status;
    @JsonProperty("injury_status")     private String injuryStatus;     // Questionable / Doubtful / Out / IR / PUP / Sus / null
    @JsonProperty("injury_body_part")  private String injuryBodyPart;   // Knee / Hamstring / Undisclosed / null
    @JsonProperty("depth_chart_order") private Integer depthChartOrder;
    @JsonProperty("years_exp")   private Integer yearsExp;

    public String getPlayerId()          { return playerId; }
    public String getFullName()          { return fullName; }
    public String getFirstName()         { return firstName; }
    public String getLastName()          { return lastName; }
    public String getPosition()          { return position; }
    public String getTeam()              { return team; }
    public Integer getAge()              { return age; }
    public String getBirthDate()         { return birthDate; }
    public String getStatus()            { return status; }
    public String getInjuryStatus()      { return injuryStatus; }
    public String getInjuryBodyPart()    { return injuryBodyPart; }
    public Integer getDepthChartOrder()  { return depthChartOrder; }
    public Integer getYearsExp()         { return yearsExp; }
}
