package dev.igherga.moneymind.budget;

import static org.assertj.core.api.Assertions.assertThat;

import dev.igherga.moneymind.reporting.TxnKind;
import dev.igherga.moneymind.transaction.TransactionCommand;
import dev.igherga.moneymind.transaction.TransactionService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Setting budgets, and the relationship between a budget and the spending
 * measured against it.
 *
 * <p>The key property under test: spending comes only from the ledger. Setting
 * a budget never changes it, and recording a transaction always does.
 */
@SpringBootTest(properties = {
        "moneymind.demo-data.enabled=false",
        "moneymind.default-currency=EUR",
})
class BudgetServiceTest {

    private static final YearMonth MONTH = YearMonth.of(2026, 9);

    @DynamicPropertySource
    static void useTemporaryDatabase(DynamicPropertyRegistry registry) throws IOException {
        Path dir = Files.createTempDirectory("moneymind-budget-test");
        registry.add("spring.datasource.url",
                () -> "jdbc:sqlite:" + dir.resolve("test.db") + "?journal_mode=WAL&foreign_keys=on");
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private BudgetService budgets;

    @Autowired
    private TransactionService transactions;

    private long current;
    private long food;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM monthly_budget");
        jdbc.update("DELETE FROM budget_period");
        jdbc.update("DELETE FROM budget");
        jdbc.update("DELETE FROM txn");
        jdbc.update("DELETE FROM transfer");
        jdbc.update("DELETE FROM account");

        jdbc.update("INSERT INTO account (name, type, currency) VALUES ('Current', 'CURRENT', 'EUR')");
        current = jdbc.queryForObject("SELECT id FROM account WHERE name = 'Current'", Long.class);
        food = jdbc.queryForObject("SELECT id FROM category WHERE system_key = 'FOOD'", Long.class);
    }

    @Test
    @DisplayName("the everyday categories from the migration are available to budget")
    void everydayCategoriesExist() {
        assertThat(budgets.view(MONTH).categories())
                .extracting(CategoryBudget::name)
                .contains("Food", "Household", "Personal", "Unpredicted", "Health");
    }

    @Test
    @DisplayName("no budget set is reported as unset, not as a budget of zero")
    void unsetIsNotZero() {
        MonthlyBudgetView view = budgets.view(MONTH);

        assertThat(view.allocated().isZero()).isTrue();
        assertThat(view.categories()).allSatisfy(
                category -> assertThat(category.isBudgeted()).isFalse());
    }

    @Test
    @DisplayName("setting the monthly budget records it and computes what is left")
    void setMonthlyBudget() {
        spend(4210);

        MonthlyBudgetView view = budgets.setMonthlyBudget(MONTH, 200000);

        assertThat(view.allocated().minorUnits()).isEqualTo(200000);
        assertThat(view.spent().minorUnits()).isEqualTo(-4210);
        assertThat(view.remaining().minorUnits()).isEqualTo(195790);
        assertThat(view.inherited()).isFalse();
    }

    @Test
    @DisplayName("overspending is reported as a negative remainder rather than clamped")
    void overspendingIsNotClamped() {
        spend(250000);

        MonthlyBudgetView view = budgets.setMonthlyBudget(MONTH, 200000);

        assertThat(view.remaining().minorUnits()).isEqualTo(-50000);
        assertThat(view.consumedFraction()).isGreaterThan(1.0d);
    }

    @Test
    @DisplayName("a budget set once carries forward to later months, and says that it did")
    void budgetCarriesForward() {
        budgets.setMonthlyBudget(MONTH, 200000);

        MonthlyBudgetView later = budgets.view(MONTH.plusMonths(2));

        assertThat(later.allocated().minorUnits()).isEqualTo(200000);
        // Flagged, so the UI never implies the user chose it for this month.
        assertThat(later.inherited()).isTrue();
    }

    @Test
    @DisplayName("an earlier month is unaffected by a budget set later")
    void earlierMonthsAreNotBackfilled() {
        budgets.setMonthlyBudget(MONTH, 200000);

        assertThat(budgets.view(MONTH.minusMonths(1)).allocated().isZero()).isTrue();
    }

    @Test
    @DisplayName("a zero amount clears the budget")
    void zeroClearsTheBudget() {
        budgets.setMonthlyBudget(MONTH, 200000);

        assertThat(budgets.setMonthlyBudget(MONTH, 0).allocated().isZero()).isTrue();
    }

    @Test
    @DisplayName("a category budget is stored for the month and reports its spend")
    void setCategoryBudget() {
        spend(4210);

        MonthlyBudgetView view = budgets.setCategoryBudget(MONTH, food, 32000);

        CategoryBudget line = categoryNamed(view, "Food");
        assertThat(line.isBudgeted()).isTrue();
        assertThat(line.allocated().minorUnits()).isEqualTo(32000);
        assertThat(line.spent().minorUnits()).isEqualTo(-4210);
    }

    @Test
    @DisplayName("changing a category budget replaces it rather than adding a second")
    void categoryBudgetIsReplaced() {
        budgets.setCategoryBudget(MONTH, food, 32000);
        MonthlyBudgetView view = budgets.setCategoryBudget(MONTH, food, 40000);

        assertThat(categoryNamed(view, "Food").allocated().minorUnits()).isEqualTo(40000);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM budget_period", Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a zero category budget removes it, returning to unbudgeted")
    void zeroClearsACategoryBudget() {
        budgets.setCategoryBudget(MONTH, food, 32000);

        MonthlyBudgetView view = budgets.setCategoryBudget(MONTH, food, 0);

        assertThat(categoryNamed(view, "Food").isBudgeted()).isFalse();
    }

    @Test
    @DisplayName("a transfer out of the account never consumes a budget")
    void transfersDoNotConsumeBudgets() {
        jdbc.update("INSERT INTO account (name, type, currency) VALUES ('Savings', 'SAVINGS', 'EUR')");
        long savings = jdbc.queryForObject(
                "SELECT id FROM account WHERE name = 'Savings'", Long.class);
        transactions.transfer(new dev.igherga.moneymind.transaction.TransferCommand(
                current, savings, 40000, MONTH.atDay(15), "Monthly saving"));

        MonthlyBudgetView view = budgets.setMonthlyBudget(MONTH, 200000);

        assertThat(view.spent().isZero()).isTrue();
        assertThat(view.remaining().minorUnits()).isEqualTo(200000);
    }

    @Test
    @DisplayName("a refund reduces the budget's spend rather than counting as income")
    void refundReducesBudgetSpend() {
        spend(10000);
        transactions.create(new TransactionCommand(
                current, food, TxnKind.EXPENSE, 2500, MONTH.atDay(6), "Refund", null));

        MonthlyBudgetView view = budgets.setCategoryBudget(MONTH, food, 32000);

        assertThat(categoryNamed(view, "Food").spent().minorUnits()).isEqualTo(-7500);
    }

    @Test
    @DisplayName("spending in another month does not count against this month's budget")
    void spendIsScopedToTheMonth() {
        transactions.create(new TransactionCommand(
                current, food, TxnKind.EXPENSE, -9999, MONTH.minusMonths(1).atDay(15),
                "Last month", null));

        assertThat(budgets.setMonthlyBudget(MONTH, 200000).spent().isZero()).isTrue();
    }

    private CategoryBudget categoryNamed(MonthlyBudgetView view, String name) {
        return view.categories().stream()
                .filter(category -> category.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no category " + name));
    }

    private void spend(long magnitude) {
        transactions.create(new TransactionCommand(
                current, food, TxnKind.EXPENSE, -magnitude, MONTH.atDay(5), "Supermarket", null));
    }
}
