package com.neuroplan.auth.health;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RouteHealthController {

    private final JdbcTemplate jdbcTemplate;
    private final ApplicationAvailability applicationAvailability;
    private final boolean readinessFailureEnabled;

    public RouteHealthController(
            JdbcTemplate jdbcTemplate,
            ApplicationAvailability applicationAvailability,
            @Value("${app.deployment-safety.readiness-fail:false}") boolean readinessFailureEnabled) {
        this.jdbcTemplate = jdbcTemplate;
        this.applicationAvailability = applicationAvailability;
        this.readinessFailureEnabled = readinessFailureEnabled;
    }

    @GetMapping("/health/ready")
    ResponseEntity<Map<String, String>> ready() {
        boolean acceptingTraffic = applicationAvailability.getReadinessState() == ReadinessState.ACCEPTING_TRAFFIC;
        boolean databaseReady = false;

        try {
            Integer value = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            databaseReady = value != null && value == 1;
        } catch (DataAccessException ignored) {
            databaseReady = false;
        }

        if (acceptingTraffic && databaseReady && !readinessFailureEnabled) {
            return ResponseEntity.ok(Map.of("status", "ok"));
        }

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("status", "unavailable"));
    }
}
