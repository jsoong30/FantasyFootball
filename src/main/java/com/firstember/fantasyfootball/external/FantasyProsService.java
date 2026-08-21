package com.firstember.fantasyfootball.external;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * FantasyPros' consensus season-long PPR projections (aggregated across ESPN, CBS Sports,
 * FFToday, etc). Requires a free personal API key (non-commercial use) — see
 * fantasypros.com/api-data. Used the same way as FantasyCalculatorService's ADP: a
 * forward-looking market signal none of our trained features can see, since it bakes in
 * offseason roster/scheme news a box-score-only model has no way to know about.
 */
@Service
public class FantasyProsService {

    private static final Logger log = LoggerFactory.getLogger(FantasyProsService.class);
    private static final String BASE = "https://api.fantasypros.com/public/v2/json/nfl";
    private static final List<String> POSITIONS = List.of("QB", "RB", "WR", "TE");

    private final RestTemplate restTemplate;

    @Value("${fantasypros.api.key:}")
    private String apiKey;

    public FantasyProsService(RestTemplateBuilder builder) {
        this.restTemplate = builder
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(20))
                .build();
    }

    /**
     * Consensus PPR season-point projections for the given season, keyed by
     * {@code fullName|position}. Fetches QB/RB/WR/TE in separate calls (the API takes one
     * position per request). Returns an empty map if no key is configured or any call fails —
     * this is a nice-to-have blend input, never load-bearing for the core prediction flow.
     */
    public Map<String, Double> currentProjections(int season) {
        if (apiKey == null || apiKey.isBlank()) {
            log.info("No FantasyPros API key configured — skipping consensus projections");
            return Map.of();
        }

        Map<String, Double> result = new HashMap<>();
        HttpHeaders headers = new HttpHeaders();
        headers.set("x-api-key", apiKey);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        for (String position : POSITIONS) {
            String url = BASE + "/" + season + "/projections?position=" + position + "&scoring=PPR";
            // Retry once on failure -- see FantasyCalculatorService for why a single momentary
            // network blip otherwise silently drops this guardrail's input for the whole sync.
            Exception lastError = null;
            boolean succeeded = false;
            for (int attempt = 1; attempt <= 2 && !succeeded; attempt++) {
                try {
                    ProjectionsResponse resp = restTemplate.exchange(
                            url, HttpMethod.GET, entity, ProjectionsResponse.class).getBody();
                    if (resp != null && resp.players != null) {
                        for (ProjectedPlayer p : resp.players) {
                            if (p.name == null || p.stats == null || p.stats.pointsPpr == null) continue;
                            result.put(NameUtil.key(p.name, position), p.stats.pointsPpr);
                        }
                    }
                    succeeded = true;
                } catch (Exception e) {
                    lastError = e;
                    if (attempt == 1) sleepBriefly();
                }
            }
            if (!succeeded) {
                log.warn("Could not fetch FantasyPros projections for {}: {}", position, lastError.getMessage());
            }
        }
        log.info("Fetched consensus projections for {} players from FantasyPros", result.size());
        return result;
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(400);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    // ── Response DTOs ────────────────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ProjectionsResponse {
        public List<ProjectedPlayer> players;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ProjectedPlayer {
        public String name;
        @JsonProperty("position_id") public String positionId;
        @JsonProperty("team_id") public String teamId;
        public ProjectedStats stats;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ProjectedStats {
        @JsonProperty("points_ppr") public Double pointsPpr;
    }
}
