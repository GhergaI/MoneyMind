package dev.igherga.moneymind.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.igherga.moneymind.reporting.ReportingQueries;
import dev.igherga.moneymind.reporting.TxnKind;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The write path.
 *
 * <p>Reporting tests prove the queries read the ledger correctly; these prove
 * the ledger cannot be put into a state where correct reading is impossible.
 * The two that matter most: a refund must stay an expense, and a transfer must
 * never exist as a single leg.
 */
@SpringBootTest(properties = {
        "moneymind.demo-data.enabled=false",
        "moneymind.default-currency=EUR",
})
class TransactionServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 7);

    @DynamicPropertySource
    static void useTemporaryDatabase(DynamicPropertyRegistry registry) throws IOException {
        Path dir = Files.createTempDirectory("moneymind-write-test");
        registry.add("spring.datasource.url",
                () -> "jdbc:sqlite:" + dir.resolve("test.db") + "?journal_mode=WAL&foreign_keys=on");
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionService service;

    @Autowired
    private ReportingQueries queries;

    private long current;
    private long savings;
    private long food;
    private long salary;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM savings_contribution");
        jdbc.update("DELETE FROM savings_goal");
        jdbc.update("DELETE FROM budget_period");
        jdbc.update("DELETE FROM budget");
        jdbc.update("DELETE FROM txn");
        jdbc.update("DELETE FROM transfer");
        jdbc.update("DELETE FROM account");

        current = account("Current", "CURRENT", "EUR");
        savings = account("Savings", "SAVINGS", "EUR");
        food = categoryId("FOOD");
        salary = categoryId("SALARY");
    }

    @Test
    @DisplayName("an expense is stored negative and counts as spending")
    void expenseIsStoredNegative() {
        service.create(new TransactionCommand(
                current, food, TxnKind.EXPENSE, -4210, TODAY, "Supermarket", null));

        assertThat(spend()).isEqualTo(-4210);
        assertThat(income()).isZero();
    }

    @Test
    @DisplayName("a refund is a positive expense and reduces spend without becoming income")
    void refundStaysAnExpense() {
        service.create(new TransactionCommand(
                current, food, TxnKind.EXPENSE, -4210, TODAY, "Supermarket", null));
        service.create(new TransactionCommand(
                current, food, TxnKind.EXPENSE, 1000, TODAY, "Refund", null));

        assertThat(spend()).isEqualTo(-3210);
        assertThat(income()).isZero();
    }

    @Test
    @DisplayName("a category of the wrong kind is refused with an explanation")
    void categoryKindMustMatch() {
        assertThatThrownBy(() -> service.create(new TransactionCommand(
                current, salary, TxnKind.EXPENSE, -4210, TODAY, "Wrong", null)))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessageContaining("Salary")
                .hasMessageContaining("income category");
    }

    @Test
    @DisplayName("a transaction cannot be written as a transfer through this path")
    void transferKindIsRefused() {
        assertThatThrownBy(() -> service.create(new TransactionCommand(
                current, null, TxnKind.TRANSFER, -4210, TODAY, "Nope", null)))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessageContaining("two legs");
    }

    @Test
    @DisplayName("a transaction must carry a category and a non-zero amount")
    void requiresCategoryAndAmount() {
        assertThatThrownBy(() -> service.create(new TransactionCommand(
                current, null, TxnKind.EXPENSE, -4210, TODAY, "No category", null)))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessageContaining("category");

        assertThatThrownBy(() -> service.create(new TransactionCommand(
                current, food, TxnKind.EXPENSE, 0, TODAY, "Nothing", null)))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessageContaining("non-zero");
    }

    @Test
    @DisplayName("a transfer writes exactly two equal and opposite legs")
    void transferWritesTwoLegs() {
        service.transfer(new TransferCommand(current, savings, 40000, TODAY, "Monthly saving"));

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM txn WHERE kind = 'TRANSFER'", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT SUM(amount_minor) FROM txn WHERE kind = 'TRANSFER'", Long.class)).isZero();
        // Legs never carry a category.
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM txn WHERE kind = 'TRANSFER' AND category_id IS NOT NULL",
                Integer.class)).isZero();
        // And it is neither income nor spending.
        assertThat(spend()).isZero();
        assertThat(income()).isZero();
    }

    @Test
    @DisplayName("a transfer moves both balances but leaves net worth unchanged")
    void transferMovesBalancesOnly() {
        service.create(new TransactionCommand(
                current, salary, TxnKind.INCOME, 320000, TODAY, "Salary", null));
        long before = balance(current) + balance(savings);

        service.transfer(new TransferCommand(current, savings, 40000, TODAY, "Monthly saving"));

        assertThat(balance(current)).isEqualTo(280000);
        assertThat(balance(savings)).isEqualTo(40000);
        assertThat(balance(current) + balance(savings)).isEqualTo(before);
    }

    @Test
    @DisplayName("a transfer to the same account is refused")
    void transferNeedsTwoAccounts() {
        assertThatThrownBy(() -> service.transfer(
                new TransferCommand(current, current, 40000, TODAY, "Nowhere")))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessageContaining("two different accounts");
    }

    @Test
    @DisplayName("a cross-currency transfer is refused rather than assuming a rate of one")
    void crossCurrencyTransferIsRefused() {
        long usd = account("Travel USD", "CASH", "USD");

        assertThatThrownBy(() -> service.transfer(
                new TransferCommand(current, usd, 40000, TODAY, "Holiday")))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessageContaining("exchange rate");
    }

    @Test
    @DisplayName("deleting one leg of a transfer removes the whole transfer")
    void deletingALegRemovesThePair() {
        service.transfer(new TransferCommand(current, savings, 40000, TODAY, "Monthly saving"));
        long oneLeg = jdbc.queryForObject(
                "SELECT MIN(id) FROM txn WHERE kind = 'TRANSFER'", Long.class);

        service.delete(oneLeg);

        // Leaving a single leg would make money appear out of nothing.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM txn", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transfer", Integer.class)).isZero();
    }

    @Test
    @DisplayName("a transfer leg cannot be edited on its own")
    void transferLegCannotBeEdited() {
        service.transfer(new TransferCommand(current, savings, 40000, TODAY, "Monthly saving"));
        long oneLeg = jdbc.queryForObject(
                "SELECT MIN(id) FROM txn WHERE kind = 'TRANSFER'", Long.class);

        assertThatThrownBy(() -> service.update(oneLeg, new TransactionCommand(
                current, food, TxnKind.EXPENSE, -4210, TODAY, "Hijacked", null)))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessageContaining("transfer");
    }

    @Test
    @DisplayName("an edit replaces the row and keeps the account's currency")
    void updateReplacesTheRow() {
        long id = service.create(new TransactionCommand(
                current, food, TxnKind.EXPENSE, -4210, TODAY, "Supermarket", null));

        service.update(id, new TransactionCommand(
                current, food, TxnKind.EXPENSE, -5000, TODAY, "Supermarket, corrected", null));

        assertThat(spend()).isEqualTo(-5000);
        assertThat(jdbc.queryForObject(
                "SELECT currency FROM txn WHERE id = ?", String.class, id)).isEqualTo("EUR");
        assertThat(jdbc.queryForObject(
                "SELECT manually_edited FROM txn WHERE id = ?", Integer.class, id)).isEqualTo(1);
    }

    @Test
    @DisplayName("the currency comes from the account, so the two can never disagree")
    void currencyFollowsTheAccount() {
        long usd = account("Travel USD", "CASH", "USD");
        long travel = categoryId("TRAVEL");

        service.create(new TransactionCommand(usd, travel, TxnKind.EXPENSE, -20000, TODAY, "Hotel", null));

        assertThat(jdbc.queryForObject(
                "SELECT currency FROM txn WHERE account_id = ?", String.class, usd)).isEqualTo("USD");
        // The EUR report is untouched by USD activity.
        assertThat(spend()).isZero();
    }

    @Test
    @DisplayName("deleting a missing transaction says so rather than silently doing nothing")
    void deleteMissingIsReported() {
        assertThatThrownBy(() -> service.delete(9999))
                .isInstanceOf(TransactionValidationException.class);
    }

    @Test
    @DisplayName("a written transaction shows up in the month's reporting")
    void writesAreVisibleToReporting() {
        service.create(new TransactionCommand(
                current, food, TxnKind.EXPENSE, -4210, TODAY, "Supermarket", null));

        assertThat(queries.entriesBetween("EUR", TODAY.withDayOfMonth(1),
                TODAY.withDayOfMonth(1).plusMonths(1), 50))
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.description()).isEqualTo("Supermarket");
                    assertThat(entry.kind()).isEqualTo(TxnKind.EXPENSE);
                    assertThat(entry.categoryName()).isEqualTo("Food");
                });
    }

    // --- helpers ------------------------------------------------------------

    private long spend() {
        return queries.expenseBetween("EUR", TODAY.withDayOfMonth(1),
                TODAY.withDayOfMonth(1).plusMonths(1)).minorUnits();
    }

    private long income() {
        return queries.incomeBetween("EUR", TODAY.withDayOfMonth(1),
                TODAY.withDayOfMonth(1).plusMonths(1)).minorUnits();
    }

    private long balance(long accountId) {
        return jdbc.queryForObject(
                "SELECT a.opening_balance_minor + COALESCE(SUM(t.amount_minor), 0) "
                        + "FROM account a LEFT JOIN txn t ON t.account_id = a.id "
                        + "WHERE a.id = ? GROUP BY a.id", Long.class, accountId);
    }

    private long account(String name, String type, String currency) {
        jdbc.update("INSERT INTO account (name, type, currency) VALUES (?, ?, ?)",
                name, type, currency);
        return jdbc.queryForObject("SELECT id FROM account WHERE name = ?", Long.class, name);
    }

    private long categoryId(String systemKey) {
        return jdbc.queryForObject(
                "SELECT id FROM category WHERE system_key = ?", Long.class, systemKey);
    }
}
