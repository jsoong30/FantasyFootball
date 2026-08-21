package com.firstember.fantasyfootball.external;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fantasy Football Calculator's free, keyless ADP API — built from real mock-draft data,
 * refreshed weekly. Used as a live "market rank" signal: unlike every trained model feature
 * (which only ever reflects last season's box scores), ADP is forward-looking and implicitly
 * prices in offseason news the model structurally can't see (a departed teammate, a scheme
 * change, a breakout narrative). See MlPredictionService's blend logic for how it's used.
 * <p>
 * No API key or account required — same "free public endpoint" pattern already used for the
 * ESPN schedule integration in SleeperService.
 */
@Service
public class FantasyCalculatorService {

    private static final Logger log = LoggerFactory.getLogger(FantasyCalculatorService.class);
    private static final String BASE = "https://fantasyfootballcalculator.com/api/v1/adp";

    private static final Duration CACHE_TTL = Duration.ofMinutes(10);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Simple in-memory cache -- the draft board polls this every few seconds while a draft is
    // active, and ADP genuinely doesn't move meaningfully within a single draft session, so
    // there's no reason to hit FFC's API on every poll.
    private volatile Map<String, Double> cachedAdp = Map.of();
    private volatile Instant cachedAt = Instant.EPOCH;

    public FantasyCalculatorService(RestTemplateBuilder builder) {
        this.restTemplate = builder
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(20))
                .defaultHeader("User-Agent", "Mozilla/5.0")
                .build();
    }

    /**
     * Current-draft-season overall ADP (lower = drafted earlier / more valuable), keyed by
     * {@code fullName|position} to match the lookup convention used elsewhere in the ML pipeline.
     * Returns an empty map on any failure — ADP is a nice-to-have signal, never load-bearing.
     * Cached for {@link #CACHE_TTL} across all callers (predictions blend, draft board polling).
     */
    public Map<String, Double> currentAdp(int teams) {
        if (Duration.between(cachedAt, Instant.now()).compareTo(CACHE_TTL) < 0) {
            return cachedAdp;
        }

        String url = BASE + "/ppr?teams=" + teams + "&position=all";
        // Retry once on failure -- a cold cache (right after app startup) plus one momentary
        // network blip during the single fetch window otherwise loses this signal entirely for
        // the rest of the cache TTL, which is exactly what happened diagnosing a "no route to
        // host" blip that briefly took out several unrelated external calls at once.
        Exception lastError = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                // FFC's Cloudflare-cached responses are sometimes mislabeled Content-Type:
                // text/html even though the body is valid JSON -- curl/browsers don't care, but
                // RestTemplate's getForObject() strictly validates Content-Type before picking a
                // converter and fails to deserialize. Fetching as a raw String and parsing
                // manually sidesteps that.
                String body = restTemplate.getForObject(url, String.class);
                AdpResponse resp = body != null ? objectMapper.readValue(body, AdpResponse.class) : null;
                if (resp == null || resp.players == null) return cachedAdp;

                Map<String, Double> result = new HashMap<>();
                for (AdpPlayer p : resp.players) {
                    if (p.name == null || p.position == null) continue;
                    result.put(NameUtil.key(p.name, normalizePosition(p.position)), p.adp);
                }
                log.info("Fetched ADP for {} players from Fantasy Football Calculator", result.size());
                cachedAdp = result;
                cachedAt = Instant.now();
                return cachedAdp;
            } catch (Exception e) {
                lastError = e;
                if (attempt == 1) sleepBriefly();
            }
        }
        log.warn("Could not fetch ADP from Fantasy Football Calculator after retry", lastError);
        return cachedAdp;
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(400);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /** FFC uses "DST" already for defenses, matching our convention — kept for symmetry with SleeperService. */
    private static String normalizePosition(String pos) {
        return "DEF".equals(pos) ? "DST" : pos;
    }

    // ── Response DTOs ────────────────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class AdpResponse {
        public String status;
        public List<AdpPlayer> players;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class AdpPlayer {
        @JsonProperty("player_id") public Long playerId;
        public String name;
        public String position;
        public String team;
        public Double adp;
    }
}
