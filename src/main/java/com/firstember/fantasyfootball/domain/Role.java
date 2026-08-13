package com.firstember.fantasyfootball.domain;

/** ADMIN can reach /admin/** (Sleeper syncs, ML predictions, CSV exports). Everyone else is USER. */
public enum Role {
    USER,
    ADMIN
}
