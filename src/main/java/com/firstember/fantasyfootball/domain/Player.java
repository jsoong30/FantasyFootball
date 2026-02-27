package com.firstember.fantasyfootball.domain;

import jakarta.persistence.*;

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

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
