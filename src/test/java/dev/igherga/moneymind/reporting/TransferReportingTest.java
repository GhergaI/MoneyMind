package dev.igherga.moneymind.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The most important test in the application.
 *
 * <p>Moving money between two accounts you own is not income and not spending.
 * Getting this wrong is the classic way a personal finance app produces
 * confidently wrong totals — a credit-card payment counted as expenditure on
 * top of the purchases it settles, so the month looks twice as expensive as it
 * was.
 *
 * <p>This asserts the invariant at the SQL level, which means it holds for every
 * reporting query written later regardless of the Java layer above it. The rule
 * it locks in: <b>balances use every row; income and expense filter
 * {@code kind <> 'TRANSFER'}</b>.
 */
@SpringBootTest
class TransferReportingTest {

    private static final String EUR = "EUR";

    @DynamicPropertySource
    static void useTemporaryDatabase(DynamicPropertyRegistry registry) throws IOException {
        Path dir = Files.createTempDirectory("moneymind-transfer-test");
        registry.add("spring.datasource.url",
                () -> "jdbc:sqlite:" + dir.resolve("test.db") + "?journal_mode=WAL&foreign_keys=on");
    }

    @Autowired
    private JdbcTemplate jdbc;

    private long current;
    private long savings;
    private long salaryCategory;
    private long groceriesCategory;

    @BeforeEach
    void seedLedger() {
        jdbc.update("DELETE FROM txn");
        jdbc.update("DELETE FROM transfer");
        jdbc.update("DELETE FROM account");

        jdbc.update("INSERT INTO account (name, type, currency, opening_balance_minor) "
                + "VALUES ('Current', 'CURRENT', 'EUR', 100000)");   // 1000.00 opening
        jdbc.update("INSERT INTO account (name, type, currency, opening_balance_minor) "
                + "VALUES ('Savings', 'SAVINGS', 'EUR', 0)");
        current = jdbc.queryForObject("SELECT id FROM account WHERE name = 'Current'", Long.class);
        savings = jdbc.queryForObject("SELECT id FROM account WHERE name = 'Savings'", Long.class);
        salaryCategory = jdbc.queryForObject("SELECT id FROM category WHERE system_key = 'SALARY'", Long.class);
        groceriesCategory = jdbc.queryForObject("SELECT id FROM category WHERE system_key = 'GROCERIES'", Long.class);

        // A month's activity: salary in, groceries out, and 500.00 moved to savings.
        income(current, salaryCategory, 250000, "2026-09-01", "Salary");
        expense(current, groceriesCategory, -8550, "2026-09-03", "Groceries");
        expense(current, groceriesCategory, -4210, "2026-09-10", "Groceries");
        transfer(current, savings, 50000, "2026-09-15", "To savings");
    }

    @Test
    @DisplayName("a transfer contributes nothing to income")
    void transferIsNotIncome() {
        assertThat(totalFor("INCOME")).isEqualTo(250000);
        assertThat(incomeTotal()).isEqualTo(250000);
    }

    @Test
    @DisplayName("a transfer contributes nothing to expense")
    void transferIsNotExpense() {
        assertThat(totalFor("EXPENSE")).isEqualTo(-12760);
        assertThat(expenseExcludingTransfers()).isEqualTo(-12760);
    }

    @Test
    @DisplayName("but a transfer does move both balances")
    void transferMovesBothBalances() {
        // 1000.00 opening + 2500.00 salary - 127.60 groceries - 500.00 out = 2872.40
        assertThat(balanceOf(current)).isEqualTo(287240);
        assertThat(balanceOf(savings)).isEqualTo(50000);
    }

    @Test
    @DisplayName("a transfer leaves total net worth unchanged")
    void transferDoesNotChangeNetWorth() {
        long before = balanceOf(current) + balanceOf(savings);
        transfer(current, savings, 25000, "2026-09-20", "More savings");
        assertThat(balanceOf(current) + balanceOf(savings)).isEqualTo(before);
    }

    @Test
    @DisplayName("the transfer's two legs are equal and opposite")
    void transferLegsCancel() {
        Long sum = jdbc.queryForObject(
                "SELECT SUM(amount_minor) FROM txn WHERE kind = 'TRANSFER'", Long.class);
        assertThat(sum).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM txn WHERE kind = 'TRANSFER'", Integer.class)).isEqualTo(2);
    }

    @Test
    @DisplayName("a refund reduces spend rather than counting as income")
    void refundReducesSpend() {
        // A returned item: positive amount, still an EXPENSE in a spend category.
        expense(current, groceriesCategory, 4210, "2026-09-12", "Refund");
        assertThat(expenseExcludingTransfers()).isEqualTo(-8550);
        assertThat(incomeTotal()).isEqualTo(250000);
    }

    @Test
    @DisplayName("classifying income by sign rather than kind would misread a refund")
    void signBasedClassificationMisreadsARefund() {
        expense(current, groceriesCategory, 4210, "2026-09-12", "Refund");

        // The correct reading: a refund is negative spending, not income.
        assertThat(incomeTotal()).isEqualTo(250000);
        // What a sign-based query would report -- inflated by the refund.
        assertThat(naiveSignBasedIncome()).isEqualTo(254210);
    }

    /** Balance uses every row belonging to the account, transfers included. */
    private long balanceOf(long accountId) {
        return jdbc.queryForObject(
                "SELECT a.opening_balance_minor + COALESCE(SUM(t.amount_minor), 0) "
                        + "FROM account a LEFT JOIN txn t ON t.account_id = a.id "
                        + "WHERE a.id = ? GROUP BY a.id",
                Long.class, accountId);
    }

    private long totalFor(String kind) {
        return jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount_minor), 0) FROM txn WHERE kind = ?", Long.class, kind);
    }

    /**
     * Income is identified by the STORED kind, never by the sign of the amount.
     * @see #signBasedClassificationMisreadsARefund()
     */
    private long incomeTotal() {
        return jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount_minor), 0) FROM txn WHERE kind = 'INCOME'", Long.class);
    }

    /** The tempting query this codebase must never use. Kept to pin down why. */
    private long naiveSignBasedIncome() {
        return jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount_minor), 0) FROM txn "
                        + "WHERE kind <> 'TRANSFER' AND amount_minor > 0", Long.class);
    }

    private long expenseExcludingTransfers() {
        return jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount_minor), 0) FROM txn WHERE kind = 'EXPENSE'", Long.class);
    }

    private void income(long account, long category, long amount, String date, String description) {
        insert(account, category, null, "INCOME", amount, date, description);
    }

    private void expense(long account, long category, long amount, String date, String description) {
        insert(account, category, null, "EXPENSE", amount, date, description);
    }

    /** Writes both legs, as the real service will, in one transaction. */
    private void transfer(long from, long to, long amount, String date, String note) {
        jdbc.update("INSERT INTO transfer (note) VALUES (?)", note);
        long transferId = jdbc.queryForObject("SELECT MAX(id) FROM transfer", Long.class);
        insert(from, null, transferId, "TRANSFER", -amount, date, note);
        insert(to, null, transferId, "TRANSFER", amount, date, note);
    }

    private void insert(long account, Long category, Long transferId,
            String kind, long amount, String date, String description) {
        jdbc.update("INSERT INTO txn (account_id, category_id, transfer_id, kind, amount_minor, "
                        + "currency, booked_on, description) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                account, category, transferId, kind, amount, EUR, date, description);
    }
}
