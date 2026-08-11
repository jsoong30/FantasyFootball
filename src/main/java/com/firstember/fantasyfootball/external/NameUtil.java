package com.firstember.fantasyfootball.external;

import java.util.regex.Pattern;

/**
 * Normalizes player names for cross-source lookups against external market data.
 * <p>
 * Different sources disagree on generational suffixes for the same player — e.g. our DB
 * stores "James Cook" (from Sleeper) while FantasyPros lists "James Cook III". Both the
 * key-building side (FantasyCalculatorService, FantasyProsService) and the lookup side
 * (MlPredictionService) must apply this identically, or matches silently fail and the
 * market-blend guardrails just never fire for that player.
 */
public final class NameUtil {

    private static final Pattern SUFFIX = Pattern.compile("\\s+(Jr\\.?|Sr\\.?|I{2,3}|IV|V)$");

    private NameUtil() {}

    public static String normalize(String fullName) {
        if (fullName == null) return null;
        return SUFFIX.matcher(fullName.trim()).replaceAll("");
    }

    public static String key(String fullName, String position) {
        return normalize(fullName) + "|" + position;
    }
}
