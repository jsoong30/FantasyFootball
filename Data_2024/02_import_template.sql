-- 02_import_template.sql
-- Use in psql after you: \c fantasydb
-- Edit the two PATH placeholders below to your actual local paths.

-- Windows example:
-- \set teams_path 'C:/Users/you/fantasy-football/Data_2024/teams.csv'
-- \set players_core_path 'C:/Users/you/fantasy-football/Data_2024/players_core.csv'

-- macOS/Linux example:
-- \set teams_path '/Users/you/fantasy-football/Data_2024/teams.csv'
-- \set players_core_path '/Users/you/fantasy-football/Data_2024/players_core.csv'

-- Staging tables that match the CSV headers exactly
DROP TABLE IF EXISTS teams_stage;
CREATE TEMP TABLE teams_stage (
  code TEXT,
  name TEXT
);

\echo Importing teams from :teams_path
\copy teams_stage (code, name) FROM :'teams_path' CSV HEADER;

-- Upsert into real teams
INSERT INTO teams (code, name)
SELECT s.code, NULLIF(s.name, '')::text
FROM teams_stage s
ON CONFLICT (code) DO UPDATE SET name = EXCLUDED.name;

-- Players staging matches players_core.csv columns
DROP TABLE IF EXISTS players_stage;
CREATE TEMP TABLE players_stage (
  player_id  TEXT,
  full_name  TEXT,
  position   TEXT,
  team_code  TEXT,
  season     TEXT,
  bye_week   TEXT,
  depth      TEXT,
  age        TEXT,
  status     TEXT
);

\echo Importing players from :players_core_path
\copy players_stage FROM :'players_core_path' CSV HEADER;

-- Load into normalized players table
INSERT INTO players (
  external_id, full_name, position, team_id, season, bye_week, depth, age, status
)
SELECT
  NULLIF(player_id,'')::text,
  NULLIF(full_name,'')::text,
  NULLIF(position,'')::text,
  t.id,
  NULLIF(season,'')::INT,
  NULLIF(bye_week,'')::INT,
  NULLIF(depth,'')::text,
  NULLIF(age,'')::INT,
  NULLIF(status,'')::text
FROM players_stage s
LEFT JOIN teams t ON t.code = s.team_code
ON CONFLICT DO NOTHING;

\echo Done. Row counts:
SELECT (SELECT COUNT(*) FROM teams)  AS teams_count,
       (SELECT COUNT(*) FROM players) AS players_count;
