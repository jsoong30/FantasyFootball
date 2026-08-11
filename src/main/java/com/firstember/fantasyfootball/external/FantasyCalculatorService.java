package com.firstember.fantasyfootball.external;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
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

    private final RestTemplate restTemplate;

    public FantasyCalculatorService(RestTemplateBuilder builder) {
        this.restTemplate = builder
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(20))
                .build();
    }

    /**
     * Current-draft-season overall ADP (lower = drafted earlier / more valuable), keyed by
     * {@code fullName|position} to match the lookup convention used elsewhere in the ML pipeline.
     * Returns an empty map on any failure — ADP is a nice-to-have signal, never load-bearing.
     */
    public Map<String, Double> currentAdp(int teams) {
        String url = BASE + "/ppr?teams=" + teams + "&position=all";
        try {
            AdpResponse resp = restTemplate.getForObject(url, AdpResponse.class);
            if (resp == null || resp.players == null) return Map.of();

            Map<String, Double> result = new HashMap<>();
            for (AdpPlayer p : resp.players) {
                if (p.name == null || p.position == null) continue;
                result.put(NameUtil.key(p.name, normalizePosition(p.position)), p.adp);
            }
            log.info("Fetched ADP for {} players from Fantasy Football Calculator", result.size());
            return result;
        } catch (Exception e) {
            log.warn("Could not fetch ADP from Fantasy Football Calculator: {}", e.getMessage());
            return Map.of();
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
