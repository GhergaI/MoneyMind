package dev.igherga.moneymind.reporting;

import dev.igherga.moneymind.common.Money;
import dev.igherga.moneymind.insight.Insight;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * Everything the dashboard shows, for one currency and one month.
 *
 * <p><b>One currency per snapshot.</b> Amounts are never summed across
 * currencies, so the snapshot names the currency it reports on and lists the
 * others that exist in {@code otherCurrencies}, letting the UI say so out loud
 * instead of quietly presenting a partial total as a complete one.
 *
 * <p>All amounts stay signed from the account's perspective: {@code monthSpend}
 * is negative, and a credit card contributes a negative balance to
 * {@code netWorth}. Flipping happens in the UI.
 *
 * @param totalBalance  what you hold — every live account except liabilities
 * @param netWorth      total balance minus what you owe, so credit cards included
 * @param savings       balance of accounts typed {@code SAVINGS} only
 * @param monthIncome   signed income for the whole calendar month
 * @param monthSpend    signed expense for the whole calendar month, so negative
 * @param monthlyBudget the overall budget set for the month, zero when unset —
 *                      which the UI must distinguish from a budget of zero
 */
public record DashboardSnapshot(
        String currency,
        YearMonth month,
        LocalDate today,
        List<String> otherCurrencies,
        Money totalBalance,
        Money netWorth,
        Money savings,
        Money monthIncome,
        Money monthSpend,
        Money monthlyBudget,
        boolean monthlyBudgetSet,
        List<AccountBalance> accounts,
        List<CategorySpend> spendingByCategory,
        List<MonthSpend> spendingTrend,
        List<BudgetProgress> budgets,
        List<LedgerEntry> recentEntries,
        List<SavingsGoalProgress> savingsGoals,
        List<Insight> insights) {

    public DashboardSnapshot {
        otherCurrencies = List.copyOf(otherCurrencies);
        accounts = List.copyOf(accounts);
        spendingByCategory = List.copyOf(spendingByCategory);
        spendingTrend = List.copyOf(spendingTrend);
        budgets = List.copyOf(budgets);
        recentEntries = List.copyOf(recentEntries);
        savingsGoals = List.copyOf(savingsGoals);
        insights = List.copyOf(insights);
    }
}
