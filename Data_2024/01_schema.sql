-- 01_schema.sql
-- Connect to your DB (in psql use: \c fantasydb)
-- This file creates normalized tables for teams and players.

CREATE TABLE IF NOT EXISTS teams (
  id   SERIAL PRIMARY KEY,
  code TEXT NOT NULL UNIQUE,    -- e.g., KC
  name TEXT NOT NULL            -- e.g., Kansas City Chiefs
);

CREATE TABLE IF NOT EXISTS players (
  id          SERIAL PRIMARY KEY,
  external_id TEXT,             -- from CSV player_id (nullable if missing)
  full_name   TEXT NOT NULL,
  position    TEXT NOT NULL,    -- QB/RB/WR/TE/K/DST
  team_id     INT REFERENCES teams(id) ON DELETE SET NULL,
  season      INT,
  bye_week    INT,
  depth       TEXT,
  age         INT,
  status      TEXT,
  CONSTRAINT uq_player UNIQUE (season, full_name, position, team_id)
);

CREATE INDEX IF NOT EXISTS idx_players_team_id ON players(team_id);
