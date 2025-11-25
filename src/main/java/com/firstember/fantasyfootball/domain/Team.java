package com.firstember.fantasyfootball.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "teams",
        uniqueConstraints = @UniqueConstraint(name = "uk_team_code", columnNames = "code"))
public class Team {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 4)
    private String code;   // e.g., KC, BUF, PHI

    @Column(nullable = false)
    private String name;   // e.g., Chiefs

    public Team() {}
    public Team(String code, String city, String name) {
        this.code = code; this.name = name;
    }

    // getters & setters
    public Long getId() { return id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}
