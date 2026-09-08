package dev.igherga.moneymind.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Guards the dashboard's queries against the invariants
 * {@code TransferReportingTest} pins at the SQL level.
 *
 * <p>{@code TransferReportingTest} proves the rules hold for hand-written SQL;
 * this proves the queries the dashboard actually issues obey them too. The two
 * failure modes it exists to catch: a transfer leaking into income or spending,
 * and a refund being read as income instead of reducing spend.
 *
 * <p>Runs against a real temporary SQLite file rather than an in-memory
 * stand-in, because partial indexes, triggers and dialect quirks are exactly
 * what would otherwise only fail at runtime.
 */
@SpringBootTest(properties = {
        // The seeder would fill the ledger before these fixtures are written.
        "moneymind.demo-data.enabled=false",
        "moneymind.default-currency=EUR",
})
class DashboardReportingTest {

    private static final String EUR = "EUR";
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 20);
    private static final YearMonth MONTH = YearMonth.of(2026, 9);
    private static final LocalDate MONTH_START = MONTH.atDay(1);
    private static final LocalDate NEXT_MONTH = MONTH.plusMonths(1).atDay(1);

    @DynamicPropertySource
    static void useTemporaryDatabase(DynamicPropertyRegistry registry) throws IOException {
        Path dir = Files.createTempDirectory("moneymind-dashboard-test");
        registry.add("spring.datasource.url",
                () -> "jdbc:sqlite:" + dir.resolve("test.db") + "?journal_mode=WAL&foreign_keys=on");
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ReportingQueries queries;

    private long current;
    private long savings;
    private long card;

    @BeforeEach
    void seedLedger() {
        jdbc.update("DELETE FROM savings_contribution");
        jdbc.update("DELETE FROM savings_goal");
        jdbc.update("DELETE FROM budget_period");
        jdbc.update("DELETE FROM budget");
        jdbc.update("DELETE FROM txn");
        jdbc.update("DELETE FROM transfer");
        jdbc.update("DELETE FROM account");

        current = account("Current", "CURRENT", EUR, 100000);
        savings = account("Savings", "SAVINGS", EUR, 0);
        card = account("Credit card", "CREDIT_CARD", EUR, 0);

        income(current, "SALARY", 320000, "2026-09-01", "Salary");
        expense(current, "GROCERIES", -8550, "2026-09-03", "Supermarket");
        expense(current, "GROCERIES", -4210, "2026-09-10", "Supermarket");
        expense(card, "EATING_OUT", -6000, "2026-09-12", "Dinner");
        transfer(current, savings, 40000, "2026-09-15", "Monthly saving");
    }

    @Test
    @DisplayName("the month's income excludes transfers")
    void incomeExcludesTransfers() {
        assertThat(queries.incomeBetween(EUR, MONTH_START, NEXT_MONTH).minorUnits())
                .isEqualTo(320000);
    }

    @Test
    @DisplayName("the month's spending excludes transfers")
    void spendingExcludesTransfers() {
        // 85.50 + 42.10 + 60.00, and not a cent of the 400.00 moved to savings.
        assertThat(queries.expenseBetween(EUR, MONTH_START, NEXT_MONTH).minorUnits())
                .isEqualTo(-18760);
    }

    @Test
    @DisplayName("a refund reduces the category's spend and never becomes income")
    void refundReducesSpend() {
        expense(current, "GROCERIES", 4210, "2026-09-11", "Refund");

        assertThat(queries.expenseBetween(EUR, MONTH_START, NEXT_MONTH).minorUnits())
                .isEqualTo(-14550);
        assertThat(queries.incomeBetween(EUR, MONTH_START, NEXT_MONTH).minorUnits())
                .isEqualTo(320000);

        CategorySpend groceries = categorySpend("Groceries");
        assertThat(groceries.spent().minorUnits()).isEqualTo(-8550);
    }

    @Test
    @DisplayName("a category refunded in full drops out of the chart rather than showing positive")
    void fullyRefundedCategoryIsOmitted() {
        expense(current, "SHOPPING", -5000, "2026-09-05", "Jacket");
        expense(current, "SHOPPING", 5000, "2026-09-06", "Returned jacket");

        assertThat(queries.spendingByCategory(EUR, MONTH_START, NEXT_MONTH))
                .extracting(CategorySpend::name)
                .doesNotContain("Shopping");
    }

    @Test
    @DisplayName("balances include transfer legs")
    void balancesIncludeTransfers() {
        List<AccountBalance> balances = queries.accountBalances();

        // 1000.00 opening + 3200.00 salary - 127.60 groceries - 400.00 to savings
        assertThat(balanceOf(balances, "Current").minorUnits()).isEqualTo(367240);
        assertThat(balanceOf(balances, "Savings").minorUnits()).isEqualTo(40000);
        // The card holds a negative balance, meaning "you owe".
        assertThat(balanceOf(balances, "Credit card").minorUnits()).isEqualTo(-6000);
    }

    @Test
    @DisplayName("spending by category is ordered biggest first and rolls sub-categories up")
    void categoriesRollUpToParents() {
        long groceries = categoryId("GROCERIES");
        jdbc.update("INSERT INTO category (parent_id, name, kind, sort_order) "
                + "VALUES (?, 'Bakery', 'EXPENSE', 31)", groceries);
        long bakery = jdbc.queryForObject(
                "SELECT id FROM category WHERE name = 'Bakery'", Long.class);
        insert(current, bakery, null, "EXPENSE", -3000, "2026-09-14", "Bread");

        List<CategorySpend> spend = queries.spendingByCategory(EUR, MONTH_START, NEXT_MONTH);

        assertThat(spend).extracting(CategorySpend::name)
                .doesNotContain("Bakery");
        // 85.50 + 42.10 + 30.00 of bakery folded in.
        assertThat(categorySpend("Groceries").spent().minorUnits()).isEqualTo(-15760);
        assertThat(spend.getFirst().name()).isEqualTo("Groceries");
    }

    @Test
    @DisplayName("the trend fills months that have no activity with zero")
    void trendFillsQuietMonths() {
        List<MonthSpend> trend = queries.spendingTrend(EUR, MONTH, 6);

        assertThat(trend).hasSize(6);
        assertThat(trend.getFirst().month()).isEqualTo(YearMonth.of(2026, 4));
        assertThat(trend.getFirst().spent().minorUnits()).isZero();
        assertThat(trend.getLast().month()).isEqualTo(MONTH);
        assertThat(trend.getLast().spent().minorUnits()).isEqualTo(-18760);
    }

    @Test
    @DisplayName("budget actuals count the category's spend and no transfers")
    void budgetProgressCountsSpendOnly() {
        budget("GROCERIES", 32000, MONTH_START, MONTH.atEndOfMonth());

        List<BudgetProgress> progress = queries.budgetProgress(EUR, TODAY);

        assertThat(progress).hasSize(1);
        BudgetProgress groceries = progress.getFirst();
        assertThat(groceries.categoryName()).isEqualTo("Groceries");
        assertThat(groceries.allocated().minorUnits()).isEqualTo(32000);
        assertThat(groceries.spent().minorUnits()).isEqualTo(-12760);
        assertThat(groceries.consumedFraction()).isCloseTo(0.39875d, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    @DisplayName("a budget period that does not contain today is not reported")
    void budgetProgressIgnoresOtherPeriods() {
        budget("GROCERIES", 32000, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));

        assertThat(queries.budgetProgress(EUR, TODAY)).isEmpty();
    }

    @Test
    @DisplayName("an account-backed savings goal reads the account's real balance")
    void savingsGoalFollowsItsAccount() {
        jdbc.update("""
                INSERT INTO savings_goal (name, target_minor, currency, target_date, account_id,
                                          funding_mode, created_at)
                VALUES ('Emergency fund', 600000, 'EUR', '2027-05-01', ?, 'ACCOUNT_BALANCE', ?)
                """, savings, "2026-04-01T00:00:00");

        List<SavingsGoalProgress> goals = queries.savingsGoals(EUR);

        assertThat(goals).hasSize(1);
        assertThat(goals.getFirst().saved().minorUnits()).isEqualTo(40000);
        assertThat(goals.getFirst().reachedFraction()).isCloseTo(
                0.0666d, org.assertj.core.data.Offset.offset(1e-3));
    }

    @Test
    @DisplayName("recent activity lists transfer legs, labelled as transfers")
    void recentActivityLabelsTransfers() {
        List<LedgerEntry> recent = queries.recentEntries(EUR, 8);

        assertThat(recent.getFirst().bookedOn()).isEqualTo(LocalDate.of(2026, 9, 15));
        assertThat(recent.getFirst().kind()).isEqualTo(TxnKind.TRANSFER);
        // A transfer leg never carries a category.
        assertThat(recent.getFirst().categoryName()).isNull();
    }

    @Test
    @DisplayName("a second currency is never folded into the reported totals")
    void otherCurrenciesAreNotSummed() {
        long usdAccount = account("Travel USD", "CASH", "USD", 50000);
        insert(usdAccount, categoryId("TRAVEL"), null, "EXPENSE", -20000, "2026-09-09", "Hotel");

        // The EUR figures are untouched by the USD activity.
        assertThat(queries.expenseBetween(EUR, MONTH_START, NEXT_MONTH).minorUnits())
                .isEqualTo(-18760);
        assertThat(queries.expenseBetween("USD", MONTH_START, NEXT_MONTH).minorUnits())
                .isEqualTo(-20000);
        assertThat(queries.activeCurrencies()).contains(EUR, "USD");
    }

    // --- fixtures -----------------------------------------------------------

    private CategorySpend categorySpend(String name) {
        return queries.spendingByCategory(EUR, MONTH_START, NEXT_MONTH).stream()
                .filter(spend -> spend.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no spend for category " + name));
    }

    private dev.igherga.moneymind.common.Money balanceOf(List<AccountBalance> balances, String name) {
        return balances.stream()
                .filter(account -> account.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no account " + name))
                .balance();
    }

    private long account(String name, String type, String currency, long opening) {
        jdbc.update("INSERT INTO account (name, type, currency, opening_balance_minor) "
                + "VALUES (?, ?, ?, ?)", name, type, currency, opening);
        return jdbc.queryForObject("SELECT id FROM account WHERE name = ?", Long.class, name);
    }

    private void budget(String systemKey, long allocated, LocalDate startsOn, LocalDate endsOn) {
        jdbc.update("""
                INSERT INTO budget (category_id, default_amount_minor, period_type, anchor_day,
                                    rollover, currency, active_from)
                VALUES (?, ?, 'MONTHLY', 1, 0, 'EUR', ?)
                """, categoryId(systemKey), allocated, startsOn.toString());
        long budgetId = jdbc.queryForObject("SELECT MAX(id) FROM budget", Long.class);
        jdbc.update("""
                INSERT INTO budget_period (budget_id, starts_on, ends_on, allocated_minor, rollover_in_minor)
                VALUES (?, ?, ?, ?, 0)
                """, budgetId, startsOn.toString(), endsOn.toString(), allocated);
    }

    private void income(long account, String systemKey, long amount, String date, String description) {
        insert(account, categoryId(systemKey), null, "INCOME", amount, date, description);
    }

    private void expense(long account, String systemKey, long amount, String date, String description) {
        insert(account, categoryId(systemKey), null, "EXPENSE", amount, date, description);
    }

    private void transfer(long from, long to, long amount, String date, String note) {
        jdbc.update("INSERT INTO transfer (note) VALUES (?)", note);
        long transferId = jdbc.queryForObject("SELECT MAX(id) FROM transfer", Long.class);
        insert(from, null, transferId, "TRANSFER", -amount, date, note);
        insert(to, null, transferId, "TRANSFER", amount, date, note);
    }

    private void insert(long account, Long category, Long transferId, String kind,
            long amount, String date, String description) {
        String currency = jdbc.queryForObject(
                "SELECT currency FROM account WHERE id = ?", String.class, account);
        jdbc.update("INSERT INTO txn (account_id, category_id, transfer_id, kind, amount_minor, "
                        + "currency, booked_on, description) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                account, category, transferId, kind, amount, currency, date, description);
    }

    private long categoryId(String systemKey) {
        return jdbc.queryForObject(
                "SELECT id FROM category WHERE system_key = ?", Long.class, systemKey);
    }
}
