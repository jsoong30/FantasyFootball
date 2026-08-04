package com.firstember.fantasyfootball.domain;

import jakarta.persistence.*;

import java.time.LocalDate;

@Entity
@Table(name = "players",
        uniqueConstraints = @UniqueConstraint(name = "uq_player", columnNames = {"season", "full_name", "position", "team_id"}))
public class Player {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "external_id")
    private String externalId;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(nullable = false)
    private String position;   // QB / RB / WR / TE / K / DST

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id")
    private Team team;

    private Integer season;

    @Column(name = "bye_week")
    private Integer byeWeek;

    private String depth;

    private Integer age;

    // Sleeper's date of birth for this player. Used to compute a season-accurate
    // `age` instead of stamping the player's current age onto every historical
    // season row (Sleeper's /players/nfl feed only ever returns today's age).
    @Column(name = "birth_date")
    private LocalDate birthDate;

    // Sleeper's *current* roster status (e.g. "Active", "Inactive", "Injured Reserve").
    // This is a live snapshot, not season-specific history — every season row for a
    // player gets whatever status was true the last time a sync ran. Do NOT use this
    // as a historical training feature. It's only meaningful as a prediction-time
    // signal (see MlPredictionService / serve.py status guardrail).
    private String status;

    public Player() {}

    // getters & setters
    public Long getId() { return id; }

    public String getExternalId() { return externalId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }

    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }

    public String getPosition() { return position; }
    public void setPosition(String position) { this.position = position; }

    public Team getTeam() { return team; }
    public void setTeam(Team team) { this.team = team; }

    public Integer getSeason() { return season; }
    public void setSeason(Integer season) { this.season = season; }

    public Integer getByeWeek() { return byeWeek; }
    public void setByeWeek(Integer byeWeek) { this.byeWeek = byeWeek; }

    public String getDepth() { return depth; }
    public void setDepth(String depth) { this.depth = depth; }

    public Integer getAge() { return age; }
    public void setAge(Integer age) { this.age = age; }

    public LocalDate getBirthDate() { return birthDate; }
    public void setBirthDate(LocalDate birthDate) { this.birthDate = birthDate; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
