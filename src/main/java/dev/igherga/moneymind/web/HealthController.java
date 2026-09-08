package dev.igherga.moneymind.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 0 liveness check. Deliberately reports the applied schema version and
 * row counts, so a single request confirms that Flyway ran and the seed data
 * landed rather than merely that the process is up.
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    private final JdbcTemplate jdbc;
    private final String defaultCurrency;

    HealthController(JdbcTemplate jdbc, @Value("${moneymind.default-currency}") String defaultCurrency) {
        this.jdbc = jdbc;
        this.defaultCurrency = defaultCurrency;
    }

    @GetMapping("/health")
    public Health health() {
        String schemaVersion = jdbc.queryForObject(
                "SELECT MAX(version) FROM flyway_schema_history WHERE success = 1", String.class);
        Integer accounts = jdbc.queryForObject("SELECT COUNT(*) FROM account", Integer.class);
        Integer categories = jdbc.queryForObject("SELECT COUNT(*) FROM category", Integer.class);
        Integer transactions = jdbc.queryForObject("SELECT COUNT(*) FROM txn", Integer.class);
        return new Health("UP", schemaVersion, defaultCurrency, accounts, categories, transactions);
    }

    public record Health(
            String status,
            String schemaVersion,
            String defaultCurrency,
            Integer accounts,
            Integer categories,
            Integer transactions) {
    }
}
