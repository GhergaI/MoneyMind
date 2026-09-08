package dev.igherga.moneymind.reporting;

import dev.igherga.moneymind.budget.BudgetRepository;
import dev.igherga.moneymind.common.Money;
import dev.igherga.moneymind.insight.AnalysisContext;
import dev.igherga.moneymind.insight.Insight;
import dev.igherga.moneymind.insight.InsightService;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Assembles the dashboard from the reporting queries and the insight rules.
 *
 * <p>Read in one transaction so every figure on the screen describes the same
 * instant. Without that, a write landing between two of these queries could
 * render a balance that disagrees with the transaction list explaining it.
 *
 * <p><b>Two different windows, on purpose.</b> The headline income and spending
 * tiles cover the whole calendar month, which is what "this month" means to a
 * reader. The month-over-month insight instead compares the month <em>to
 * date</em> against the same number of days of the previous month, because
 * comparing four elapsed days with a complete month would report an improvement
 * every month until the last week of it.
 */
@Service
public class DashboardService {

    /** How many ledger rows the recent-activity list shows. */
    private static final int RECENT_LIMIT = 8;

    /** Months of history in the trend chart, current month included. */
    private static final int TREND_MONTHS = 6;

    private final ReportingQueries queries;
    private final BudgetRepository budgets;
    private final InsightService insights;
    private final Clock clock;
    private final String defaultCurrency;

    DashboardService(ReportingQueries queries, BudgetRepository budgets, InsightService insights,
            Clock clock, @Value("${moneymind.default-currency}") String defaultCurrency) {
        this.queries = queries;
        this.budgets = budgets;
        this.insights = insights;
        this.clock = clock;
        this.defaultCurrency = Money.zero(defaultCurrency).currency();
    }

    @Transactional(readOnly = true)
    public DashboardSnapshot snapshot() {
        LocalDate today = LocalDate.now(clock);
        YearMonth month = YearMonth.from(today);

        List<String> currencies = queries.activeCurrencies();
        String currency = reportingCurrency(currencies);
        List<String> others = currencies.stream().filter(code -> !code.equals(currency)).toList();

        LocalDate monthStart = month.atDay(1);
        LocalDate nextMonthStart = month.plusMonths(1).atDay(1);

        List<AccountBalance> accounts = queries.accountBalances().stream()
                .filter(account -> account.balance().currency().equals(currency))
                .toList();

        Optional<BudgetRepository.EffectiveBudget> monthlyBudget =
                budgets.effectiveMonthlyBudget(currency, month);

        DashboardSnapshot snapshot = new DashboardSnapshot(
                currency,
                month,
                today,
                others,
                totalOf(currency, accounts, AccountBalance::isAsset),
                totalOf(currency, accounts, account -> true),
                totalOf(currency, accounts, account -> account.type() == AccountType.SAVINGS),
                queries.incomeBetween(currency, monthStart, nextMonthStart),
                queries.expenseBetween(currency, monthStart, nextMonthStart),
                monthlyBudget.map(BudgetRepository.EffectiveBudget::amount)
                        .orElse(Money.zero(currency)),
                monthlyBudget.isPresent(),
                accounts,
                queries.spendingByCategory(currency, monthStart, nextMonthStart),
                queries.spendingTrend(currency, month, TREND_MONTHS),
                queries.budgetProgress(currency, today),
                queries.recentEntries(currency, RECENT_LIMIT),
                queries.savingsGoals(currency),
                List.of());

        return withInsights(snapshot, monthStart, today);
    }

    /**
     * The currency to report on: the configured default when it is actually in
     * use, otherwise the most-used one. Reporting on a default that no account
     * holds would show a dashboard of zeroes next to a full ledger.
     */
    private String reportingCurrency(List<String> currencies) {
        if (currencies.isEmpty() || currencies.contains(defaultCurrency)) {
            return defaultCurrency;
        }
        return currencies.getFirst();
    }

    private Money totalOf(String currency, List<AccountBalance> accounts,
            java.util.function.Predicate<AccountBalance> include) {
        List<Money> amounts = new ArrayList<>();
        for (AccountBalance account : accounts) {
            if (include.test(account)) {
                amounts.add(account.balance());
            }
        }
        // Money.sum starts from zero, so an empty selection is a real zero
        // rather than an absent value the UI would have to special-case.
        return Money.sum(currency, amounts);
    }

    private DashboardSnapshot withInsights(DashboardSnapshot snapshot, LocalDate monthStart, LocalDate today) {
        List<Insight> found = insights.analyse(new AnalysisContext(
                snapshot.currency(),
                snapshot.month(),
                today,
                queries.expenseBetween(snapshot.currency(), monthStart, today.plusDays(1)),
                previousPeriodSpend(snapshot.currency(), snapshot.month(), today),
                snapshot.monthIncome(),
                snapshot.spendingByCategory(),
                snapshot.savingsGoals()));

        return new DashboardSnapshot(
                snapshot.currency(), snapshot.month(), snapshot.today(), snapshot.otherCurrencies(),
                snapshot.totalBalance(), snapshot.netWorth(), snapshot.savings(),
                snapshot.monthIncome(), snapshot.monthSpend(),
                snapshot.monthlyBudget(), snapshot.monthlyBudgetSet(), snapshot.accounts(),
                snapshot.spendingByCategory(), snapshot.spendingTrend(), snapshot.budgets(),
                snapshot.recentEntries(), snapshot.savingsGoals(), found);
    }

    /**
     * Previous month's spend over the same number of elapsed days as the current
     * month has had.
     *
     * <p>The end of the window is clamped to the end of the previous month, so
     * comparing on the 31st against a 30-day month stops at that month's end
     * instead of spilling into the current one and double-counting days.
     */
    private Money previousPeriodSpend(String currency, YearMonth month, LocalDate today) {
        YearMonth previous = month.minusMonths(1);
        LocalDate previousStart = previous.atDay(1);
        LocalDate sameElapsedEnd = previousStart.plusDays(today.getDayOfMonth());
        LocalDate previousMonthEnd = previous.plusMonths(1).atDay(1);
        LocalDate end = sameElapsedEnd.isBefore(previousMonthEnd) ? sameElapsedEnd : previousMonthEnd;
        return queries.expenseBetween(currency, previousStart, end);
    }
}
