package dev.igherga.moneymind.insight;

import dev.igherga.moneymind.common.Money;
import dev.igherga.moneymind.reporting.CategorySpend;
import dev.igherga.moneymind.reporting.SavingsGoalProgress;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * Everything the rules are allowed to look at, computed once per request.
 *
 * <p>Rules receive this and nothing else — no repository, no {@code JdbcTemplate}
 * — which is what keeps them plain objects that unit-test without a Spring
 * context, and stops a rule quietly issuing its own query with different
 * transfer semantics than the rest of the app.
 *
 * <p><b>The comparison window matters.</b> {@code spendThisMonth} covers the
 * month up to and including today, and {@code spendPreviousPeriod} covers the
 * <em>same number of elapsed days</em> of the previous month. Comparing a
 * three-day-old month against a complete one would report "you're spending
 * less" every month for the first three weeks, which is flattering and useless.
 *
 * @param currency            the single currency being reported on
 * @param month               the month under analysis
 * @param today               the date the analysis is anchored to
 * @param spendThisMonth      signed expense total, month start through today
 * @param spendPreviousPeriod signed expense total over the equivalent window of
 *                            the previous month
 * @param incomeThisMonth     signed income total for the month so far
 * @param spendByCategory     per-category spend for the month, biggest first
 * @param goals               live savings goals with their progress
 */
public record AnalysisContext(
        String currency,
        YearMonth month,
        LocalDate today,
        Money spendThisMonth,
        Money spendPreviousPeriod,
        Money incomeThisMonth,
        List<CategorySpend> spendByCategory,
        List<SavingsGoalProgress> goals) {

    public AnalysisContext {
        // elapsedDays() below reads the day-of-month straight off `today`, which
        // is only the elapsed length of `month` when the two agree. Fail loudly
        // rather than silently produce a wrong comparison window.
        if (!YearMonth.from(today).equals(month)) {
            throw new IllegalArgumentException(
                    "today " + today + " must fall inside the analysed month " + month);
        }
        spendByCategory = List.copyOf(spendByCategory);
        goals = List.copyOf(goals);
    }

    /** How many days of {@code month} have elapsed, today included. */
    public int elapsedDays() {
        return today.getDayOfMonth();
    }
}
