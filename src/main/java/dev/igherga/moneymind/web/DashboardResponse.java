package dev.igherga.moneymind.web;

import dev.igherga.moneymind.insight.Insight;
import dev.igherga.moneymind.reporting.AccountBalance;
import dev.igherga.moneymind.reporting.BudgetProgress;
import dev.igherga.moneymind.reporting.CategorySpend;
import dev.igherga.moneymind.reporting.DashboardSnapshot;
import dev.igherga.moneymind.reporting.LedgerEntry;
import dev.igherga.moneymind.reporting.MonthSpend;
import dev.igherga.moneymind.reporting.SavingsGoalProgress;

import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The dashboard as JSON.
 *
 * <p>Amounts cross the wire as {@code *Minor} integers with the currency named
 * once at the top, never as decimals: a JSON number is an IEEE double in most
 * clients, and {@code 0.1} does not survive the trip. The UI turns minor units
 * into text using the currency's own exponent.
 *
 * <p>Signs are preserved exactly as stored — {@code monthSpendMinor} is
 * negative. The UI flips them for display, so this stays the single honest
 * representation.
 */
public record DashboardResponse(
        String currency,
        String month,
        String monthLabel,
        String today,
        List<String> otherCurrencies,
        Totals totals,
        MonthlyBudget monthlyBudget,
        List<AccountLine> accounts,
        List<CategorySlice> spendingByCategory,
        List<TrendPoint> spendingTrend,
        List<BudgetLine> budgets,
        List<EntryLine> recentTransactions,
        List<GoalLine> savingsGoals,
        List<InsightCard> insights) {

    private static final DateTimeFormatter MONTH_KEY = DateTimeFormatter.ofPattern("yyyy-MM");

    public static DashboardResponse from(DashboardSnapshot snapshot) {
        Locale locale = Locale.getDefault(Locale.Category.DISPLAY);
        return new DashboardResponse(
                snapshot.currency(),
                snapshot.month().format(MONTH_KEY),
                monthLabel(snapshot.month(), locale),
                snapshot.today().toString(),
                snapshot.otherCurrencies(),
                new Totals(
                        snapshot.totalBalance().minorUnits(),
                        snapshot.netWorth().minorUnits(),
                        snapshot.savings().minorUnits(),
                        snapshot.monthIncome().minorUnits(),
                        snapshot.monthSpend().minorUnits()),
                new MonthlyBudget(
                        snapshot.monthlyBudget().minorUnits(),
                        snapshot.monthlyBudgetSet(),
                        // spend is negative, so adding it subtracts
                        snapshot.monthlyBudget().plus(snapshot.monthSpend()).minorUnits()),
                snapshot.accounts().stream().map(DashboardResponse::accountLine).toList(),
                snapshot.spendingByCategory().stream().map(DashboardResponse::categorySlice).toList(),
                snapshot.spendingTrend().stream().map(spend -> trendPoint(spend, locale)).toList(),
                snapshot.budgets().stream().map(DashboardResponse::budgetLine).toList(),
                snapshot.recentEntries().stream().map(DashboardResponse::entryLine).toList(),
                snapshot.savingsGoals().stream().map(DashboardResponse::goalLine).toList(),
                snapshot.insights().stream().map(DashboardResponse::insightCard).toList());
    }

    private static String monthLabel(java.time.YearMonth month, Locale locale) {
        return month.getMonth().getDisplayName(TextStyle.FULL, locale) + " " + month.getYear();
    }

    private static AccountLine accountLine(AccountBalance account) {
        return new AccountLine(
                account.id(), account.name(), account.type().name(),
                account.balance().minorUnits(), account.isAsset());
    }

    private static CategorySlice categorySlice(CategorySpend spend) {
        return new CategorySlice(
                spend.categoryId(), spend.name(), spend.systemKey(), spend.spent().minorUnits());
    }

    private static TrendPoint trendPoint(MonthSpend spend, Locale locale) {
        return new TrendPoint(
                spend.month().format(MONTH_KEY),
                spend.month().getMonth().getDisplayName(TextStyle.SHORT, locale),
                spend.spent().minorUnits());
    }

    private static BudgetLine budgetLine(BudgetProgress budget) {
        return new BudgetLine(
                budget.budgetId(),
                budget.categoryName(),
                budget.allocated().minorUnits(),
                budget.spent().minorUnits(),
                budget.startsOn().toString(),
                budget.endsOn().toString());
    }

    private static EntryLine entryLine(LedgerEntry entry) {
        return new EntryLine(
                entry.id(),
                entry.bookedOn().toString(),
                entry.description(),
                entry.kind().name(),
                entry.accountName(),
                entry.categoryName(),
                entry.amount().minorUnits());
    }

    private static GoalLine goalLine(SavingsGoalProgress goal) {
        return new GoalLine(
                goal.id(),
                goal.name(),
                goal.target().minorUnits(),
                goal.saved().minorUnits(),
                goal.targetDate() == null ? null : goal.targetDate().toString());
    }

    private static InsightCard insightCard(Insight insight) {
        return new InsightCard(
                insight.code(), insight.tone().name(), insight.message(), insight.facts());
    }

    /** The five headline figures, all signed, all in {@link #currency()}. */
    public record Totals(
            long totalBalanceMinor,
            long netWorthMinor,
            long savingsMinor,
            long monthIncomeMinor,
            long monthSpendMinor) {
    }

    public record AccountLine(long id, String name, String type, long balanceMinor, boolean asset) {
    }

    /**
     * The overall budget for the month, and what is left of it.
     *
     * @param allocated      whether a budget was set at all. A budget of zero and
     *                       no budget are different states, and the donut is only
     *                       meaningful in the first case
     * @param remainingMinor budget minus spending; negative means overspent, and
     *                       is not clamped
     */
    public record MonthlyBudget(long allocatedMinor, boolean allocated, long remainingMinor) {
    }

    public record CategorySlice(long categoryId, String name, String systemKey, long spentMinor) {
    }

    public record TrendPoint(String month, String label, long spentMinor) {
    }

    public record BudgetLine(
            long id, String categoryName, long allocatedMinor, long spentMinor,
            String startsOn, String endsOn) {
    }

    public record EntryLine(
            long id, String bookedOn, String description, String kind,
            String accountName, String categoryName, long amountMinor) {
    }

    public record GoalLine(long id, String name, long targetMinor, long savedMinor, String targetDate) {
    }

    /**
     * An insight. {@code facts} carries the computed values; {@code message} is
     * only their rendering, and the UI must read numbers from the former.
     */
    public record InsightCard(String code, String tone, String message, Map<String, String> facts) {
    }
}
