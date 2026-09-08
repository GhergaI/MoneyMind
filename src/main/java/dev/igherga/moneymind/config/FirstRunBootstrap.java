package dev.igherga.moneymind.config;

import dev.igherga.moneymind.common.Money;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Creates the first account on first run, so the app is immediately usable
 * without a setup step. The currency is not baked into the migration because it
 * is the one thing that genuinely varies per user; it comes from
 * {@code moneymind.default-currency} instead.
 *
 * <p>Accounts here are money containers (current account, credit card, cash) —
 * there is no login and no user table. Until a second one exists, Phase 1 hides
 * the account picker entirely, so day-to-day entry never asks about accounts.
 */
@Component
// Explicit, because other runners need to be ordered relative to this one and an
// unannotated ApplicationRunner sits at LOWEST_PRECEDENCE — leaving this bare
// would let a runner that depends on the account created here go first.
@Order(FirstRunBootstrap.ORDER)
class FirstRunBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(FirstRunBootstrap.class);

    /** Nothing that reads the ledger should run before this. */
    static final int ORDER = 0;

    private final JdbcTemplate jdbc;
    private final String defaultCurrency;

    FirstRunBootstrap(JdbcTemplate jdbc, @Value("${moneymind.default-currency}") String defaultCurrency) {
        this.jdbc = jdbc;
        // Fails fast on a typo in configuration rather than at first write.
        this.defaultCurrency = Money.zero(defaultCurrency).currency();
    }

    @Override
    public void run(ApplicationArguments args) {
        Integer existing = jdbc.queryForObject("SELECT COUNT(*) FROM account", Integer.class);
        if (existing != null && existing > 0) {
            return;
        }
        jdbc.update("INSERT INTO account (name, type, currency) VALUES ('Main', 'CURRENT', ?)",
                defaultCurrency);
        log.info("First run: created the 'Main' current account in {}", defaultCurrency);
    }
}
