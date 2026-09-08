package dev.igherga.moneymind;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Proves the baseline migration applies from empty against a real SQLite file
 * and that the schema-level invariants actually bite. These run against SQLite
 * rather than an in-memory stand-in on purpose: dialect quirks, partial-index
 * support and the CHECK syntax are exactly what would otherwise only fail at
 * runtime.
 */
@SpringBootTest
class SchemaInvariantsTest {

    @DynamicPropertySource
    static void useTemporaryDatabase(DynamicPropertyRegistry registry) throws IOException {
        Path dir = Files.createTempDirectory("moneymind-schema-test");
        Path db = dir.resolve("test.db");
        registry.add("spring.datasource.url",
                () -> "jdbc:sqlite:" + db + "?journal_mode=WAL&foreign_keys=on");
    }

    @Autowired
    private JdbcTemplate jdbc;

    private long accountId;
    private long otherAccountId;
    private long groceriesId;

    @BeforeEach
    void seedFixtures() {
        clearLedger();
        jdbc.update("INSERT INTO account (name, type, currency) VALUES ('Main', 'CURRENT', 'EUR')");
        jdbc.update("INSERT INTO account (name, type, currency) VALUES ('Savings', 'SAVINGS', 'EUR')");
        accountId = jdbc.queryForObject("SELECT id FROM account WHERE name = 'Main'", Long.class);
        otherAccountId = jdbc.queryForObject("SELECT id FROM account WHERE name = 'Savings'", Long.class);
        groceriesId = jdbc.queryForObject(
                "SELECT id FROM category WHERE system_key = 'GROCERIES'", Long.class);
    }

    @Test
    @DisplayName("every migration applied successfully")
    void migrationApplied() {
        String version = jdbc.queryForObject(
                "SELECT MAX(version) FROM flyway_schema_history WHERE success = 1", String.class);
        // Bump this with each migration: the point is to notice a migration that
        // silently failed to apply, which leaves the app running on a stale schema.
        assertThat(version).isEqualTo("0002");
    }

    @Test
    @DisplayName("every expected table exists")
    void tablesExist() {
        List<String> tables = jdbc.queryForList(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' "
                        + "AND name <> 'flyway_schema_history'", String.class);
        assertThat(tables).containsExactlyInAnyOrder(
                "account", "category", "transfer", "txn",
                "import_source_profile", "import_batch", "import_staging_row",
                "budget", "budget_period",
                "savings_goal", "savings_contribution",
                "recurring_series", "recurring_occurrence",
                "fx_rate",
                // V0002
                "monthly_budget");
    }

    @Test
    @DisplayName("starter categories are seeded with stable system keys")
    void categoriesSeeded() {
        // An exact count on purpose: seeded categories are referenced by
        // system_key from insight rules and the donut's hue table, so a
        // category appearing or vanishing unnoticed is worth failing over.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM category", Integer.class)).isEqualTo(18);
        assertThat(jdbc.queryForList("SELECT system_key FROM category", String.class))
                .contains("SALARY", "GROCERIES", "SUBSCRIPTIONS", "UNCATEGORISED",
                        // V0002's everyday categories.
                        "FOOD", "HOUSEHOLD", "PERSONAL", "UNPREDICTED", "HEALTH");
    }

    @Test
    @DisplayName("foreign keys are enforced, not silently ignored")
    void foreignKeysAreOn() {
        assertThat(jdbc.queryForObject("PRAGMA foreign_keys", Integer.class)).isEqualTo(1);
        assertThatExceptionOfType(DataAccessException.class).isThrownBy(() ->
                insertTxn(999999L, null, null, "EXPENSE", -500));
    }

    @Test
    @DisplayName("a TRANSFER row must belong to a transfer, and only a TRANSFER row may")
    void transferKindAndTransferIdAgree() {
        long transferId = insertTransfer();

        // TRANSFER without a transfer_id
        assertThatExceptionOfType(DataAccessException.class).isThrownBy(() ->
                insertTxn(accountId, null, null, "TRANSFER", -500));

        // EXPENSE that claims to be part of a transfer
        assertThatExceptionOfType(DataAccessException.class).isThrownBy(() ->
                insertTxn(accountId, null, transferId, "EXPENSE", -500));

        // The legitimate shape: two legs, equal and opposite.
        assertThatCode(() -> {
            insertTxn(accountId, null, transferId, "TRANSFER", -500);
            insertTxn(otherAccountId, null, transferId, "TRANSFER", 500);
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("transfer legs never carry a category, so they cannot consume a budget")
    void transferLegsAreUncategorised() {
        long transferId = insertTransfer();
        assertThatExceptionOfType(DataAccessException.class).isThrownBy(() ->
                insertTxn(accountId, groceriesId, transferId, "TRANSFER", -500));
    }

    @Test
    @DisplayName("a bank-supplied external id cannot be imported twice")
    void externalIdIsUniquePerAccountAndSource() {
        jdbc.update("INSERT INTO txn (account_id, kind, amount_minor, currency, booked_on, source, external_id) "
                + "VALUES (?, 'EXPENSE', -1234, 'EUR', '2026-09-01', 'CSV', 'FITID-1')", accountId);

        assertThatExceptionOfType(DataAccessException.class).isThrownBy(() ->
                jdbc.update("INSERT INTO txn (account_id, kind, amount_minor, currency, booked_on, source, external_id) "
                        + "VALUES (?, 'EXPENSE', -1234, 'EUR', '2026-09-01', 'CSV', 'FITID-1')", accountId));

        // The same id on a different account is a different transaction.
        assertThatCode(() ->
                jdbc.update("INSERT INTO txn (account_id, kind, amount_minor, currency, booked_on, source, external_id) "
                        + "VALUES (?, 'EXPENSE', -1234, 'EUR', '2026-09-01', 'CSV', 'FITID-1')", otherAccountId))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("two identical coffees on the same day both survive — dedup_hash is not a constraint")
    void duplicateDedupHashIsAllowed() {
        String sql = "INSERT INTO txn (account_id, category_id, kind, amount_minor, currency, booked_on, "
                + "description, dedup_hash) VALUES (?, ?, 'EXPENSE', -320, 'EUR', '2026-09-01', 'Coffee', 'abc123')";
        assertThatCode(() -> {
            jdbc.update(sql, accountId, groceriesId);
            jdbc.update(sql, accountId, groceriesId);
        }).doesNotThrowAnyException();

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM txn WHERE dedup_hash = 'abc123'", Integer.class)).isEqualTo(2);
    }

    @Test
    @DisplayName("a matched recurring occurrence must reference a transaction")
    void matchedOccurrenceRequiresTransaction() {
        jdbc.update("INSERT INTO recurring_series "
                + "(name, account_id, kind, expected_amount_minor, currency, freq, anchor_date) "
                + "VALUES ('Netflix', ?, 'EXPENSE', -1099, 'EUR', 'MONTHLY', '2026-01-15')", accountId);
        long seriesId = jdbc.queryForObject("SELECT id FROM recurring_series WHERE name = 'Netflix'", Long.class);

        assertThatExceptionOfType(DataAccessException.class).isThrownBy(() ->
                jdbc.update("INSERT INTO recurring_occurrence (series_id, due_on, status) "
                        + "VALUES (?, '2026-02-15', 'MATCHED')", seriesId));

        assertThatCode(() ->
                jdbc.update("INSERT INTO recurring_occurrence (series_id, due_on, status) "
                        + "VALUES (?, '2026-02-15', 'PREDICTED')", seriesId))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a savings goal in ACCOUNT_BALANCE mode must name an account")
    void accountBalanceGoalRequiresAccount() {
        assertThatExceptionOfType(DataAccessException.class).isThrownBy(() ->
                jdbc.update("INSERT INTO savings_goal (name, target_minor, currency, funding_mode) "
                        + "VALUES ('Holiday', 200000, 'EUR', 'ACCOUNT_BALANCE')"));

        assertThatCode(() ->
                jdbc.update("INSERT INTO savings_goal (name, target_minor, currency, funding_mode) "
                        + "VALUES ('Holiday', 200000, 'EUR', 'EARMARKED')"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a transaction cannot be denominated in a currency other than its account's")
    void transactionCurrencyMustMatchAccountCurrency() {
        jdbc.update("INSERT INTO account (name, type, currency) VALUES ('USD wallet', 'CASH', 'USD')");
        long usdAccount = jdbc.queryForObject(
                "SELECT id FROM account WHERE name = 'USD wallet'", Long.class);

        assertThatExceptionOfType(DataAccessException.class).isThrownBy(() ->
                jdbc.update("INSERT INTO txn (account_id, kind, amount_minor, currency, booked_on) "
                        + "VALUES (?, 'EXPENSE', -100, 'EUR', '2026-09-01')", usdAccount));

        assertThatCode(() ->
                jdbc.update("INSERT INTO txn (account_id, kind, amount_minor, currency, booked_on) "
                        + "VALUES (?, 'EXPENSE', -100, 'USD', '2026-09-01')", usdAccount))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("updated_at advances on update, instead of silently claiming creation time")
    void updatedAtAdvancesOnUpdate() {
        jdbc.update("UPDATE account SET updated_at = '2000-01-01 00:00:00' WHERE id = ?", accountId);
        jdbc.update("UPDATE account SET name = 'Main renamed' WHERE id = ?", accountId);

        String updatedAt = jdbc.queryForObject(
                "SELECT updated_at FROM account WHERE id = ?", String.class, accountId);
        assertThat(updatedAt).isNotEqualTo("2000-01-01 00:00:00");
    }

    /** Children before parents -- foreign keys are enforced here. */
    private void clearLedger() {
        for (String table : List.of("savings_contribution", "savings_goal",
                "recurring_occurrence", "recurring_series", "import_staging_row",
                "txn", "transfer", "import_batch", "account")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    private long insertTransfer() {
        jdbc.update("INSERT INTO transfer (note) VALUES ('test transfer')");
        return jdbc.queryForObject("SELECT MAX(id) FROM transfer", Long.class);
    }

    private void insertTxn(Long account, Long category, Long transfer, String kind, long amountMinor) {
        jdbc.update("INSERT INTO txn (account_id, category_id, transfer_id, kind, amount_minor, currency, booked_on) "
                        + "VALUES (?, ?, ?, ?, ?, 'EUR', '2026-09-01')",
                account, category, transfer, kind, amountMinor);
    }
}
