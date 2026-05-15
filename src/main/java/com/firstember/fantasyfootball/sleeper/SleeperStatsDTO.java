package com.firstember.fantasyfootball.sleeper;

import java.util.Map;

/**
 * Accumulates per-player stats across all weeks of a season
 * from Sleeper's weekly stats API response.
 */
public class SleeperStatsDTO {

    private double totalPoints;

    // Passing
    private int passingYds;
    private int passingTd;
    private int passingInt;

    // Rushing
    private int rushingYds;
    private int rushingTd;

    // Receiving
    private int receivingRec;
    private int receivingYds;
    private int receivingTd;
    private int targets;

    // Misc
    private int fumbles;

    // Kicker
    private int patMade;
    private int patMissed;
    private int fgMade0_19;
    private int fgMade20_29;
    private int fgMade30_39;
    private int fgMade40_49;
    private int fgMade50;
    private int fgMiss20_29;
    private int fgMiss30_39;

    // Defense / Special Teams
    private int defSacks;
    private int defInts;
    private int defFumRecoveries;
    private int defTd;
    private int defSafeties;
    private int defBlockedKicks;
    private int ptsAllowed;
    private int ydsAllowed;

    /** Add one week's raw Sleeper stat map into the running season total. */
    public void addWeek(Map<String, Object> week) {
        totalPoints  += asDouble(week, "pts_ppr");
        passingYds   += asInt(week, "pass_yd");
        passingTd    += asInt(week, "pass_td");
        passingInt   += asInt(week, "pass_int");
        rushingYds   += asInt(week, "rush_yd");
        rushingTd    += asInt(week, "rush_td");
        receivingRec += asInt(week, "rec");
        receivingYds += asInt(week, "rec_yd");
        receivingTd  += asInt(week, "rec_td");
        // Sleeper uses "rec_tgt" for targets
        targets      += asInt(week, "rec_tgt");
        fumbles      += asInt(week, "fum_lost");

        // Kicker PATs
        patMade      += asInt(week, "xpm");
        patMissed    += asInt(week, "xpmiss");

        // Field goals by distance
        fgMade0_19   += asInt(week, "fgm_0_19");
        fgMade20_29  += asInt(week, "fgm_20_29");
        fgMade30_39  += asInt(week, "fgm_30_39");
        fgMade40_49  += asInt(week, "fgm_40_49");
        fgMade50     += asInt(week, "fgm_50p");
        fgMiss20_29  += asInt(week, "fgmiss_20_29");
        fgMiss30_39  += asInt(week, "fgmiss_30_39");

        // DST — Sleeper key "int" is fine as a String map key despite being a Java reserved word
        defSacks        += asInt(week, "sack");
        defInts         += asInt(week, "int");
        defFumRecoveries += asInt(week, "fum_rec");
        defTd           += asInt(week, "def_td");
        defSafeties     += asInt(week, "safe");
        defBlockedKicks += asInt(week, "blk_kick");
        ptsAllowed      += asInt(week, "pts_allow");
        ydsAllowed      += asInt(week, "yds_allow");
    }

    public boolean hasStats() {
        return totalPoints > 0
                || passingYds > 0 || rushingYds > 0 || receivingYds > 0
                || patMade > 0 || fgMade0_19 + fgMade20_29 + fgMade30_39 + fgMade40_49 + fgMade50 > 0
                || defSacks > 0 || defInts > 0 || defFumRecoveries > 0 || defTd > 0;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static double asDouble(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v instanceof Number) return ((Number) v).doubleValue();
        return 0.0;
    }

    private static int asInt(Map<String, Object> map, String key) {
        return (int) Math.round(asDouble(map, key));
    }

    // ── Getters ──────────────────────────────────────────────────────────────

    public double getTotalPoints()  { return totalPoints; }
    public int getPassingYds()      { return passingYds; }
    public int getPassingTd()       { return passingTd; }
    public int getPassingInt()      { return passingInt; }
    public int getRushingYds()      { return rushingYds; }
    public int getRushingTd()       { return rushingTd; }
    public int getReceivingRec()    { return receivingRec; }
    public int getReceivingYds()    { return receivingYds; }
    public int getReceivingTd()     { return receivingTd; }
    public int getTargets()         { return targets; }
    public int getFumbles()         { return fumbles; }
    public int getPatMade()         { return patMade; }
    public int getPatMissed()       { return patMissed; }
    public int getFgMade0_19()      { return fgMade0_19; }
    public int getFgMade20_29()     { return fgMade20_29; }
    public int getFgMade30_39()     { return fgMade30_39; }
    public int getFgMade40_49()     { return fgMade40_49; }
    public int getFgMade50()        { return fgMade50; }
    public int getFgMiss20_29()     { return fgMiss20_29; }
    public int getFgMiss30_39()     { return fgMiss30_39; }

    public int getDefSacks()        { return defSacks; }
    public int getDefInts()         { return defInts; }
    public int getDefFumRecoveries(){ return defFumRecoveries; }
    public int getDefTd()           { return defTd; }
    public int getDefSafeties()     { return defSafeties; }
    public int getDefBlockedKicks() { return defBlockedKicks; }
    public int getPtsAllowed()      { return ptsAllowed; }
    public int getYdsAllowed()      { return ydsAllowed; }
}
