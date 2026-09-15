package com.firstember.fantasyfootball;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// @EnableScheduling just turns on the infrastructure (a thread pool + cron parsing) -- it's a
// no-op if no @Scheduled beans are active. LeagueSyncScheduler is the one that actually runs,
// gated separately by app.scheduler.enabled.
@EnableScheduling
@SpringBootApplication
public class FantasyFootballApplication {
    public static void main(String[] args) {
        SpringApplication.run(FantasyFootballApplication.class, args);
    }
}
