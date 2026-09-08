package dev.igherga.moneymind.budget;

import dev.igherga.moneymind.common.Money;

import java.time.YearMonth;
import java.util.List;

/**
 * The budget picture for one month: the overall amount, what it was spent on,
 * and what is left.
 *
 * @param allocated the overall monthly budget, zero when never set
 * @param inherited true when no amount was set for this month and the figure was
 *                  carried forward from an earlier one, so the UI can say so
 *                  rather than implying the user chose it for this month
 * @param spent     signed total expense for the month, so negative
 */
public record MonthlyBudgetView(
        YearMonth month,
        String currency,
        Money allocated,
        boolean inherited,
        Money spent,
        List<CategoryBudget> categories) {

    public MonthlyBudgetView {
        categories = List.copyOf(categories);
    }

    /**
     * Budget minus spending. Negative means overspent, which is real
     * information and is not clamped away here.
     */
    public Money remaining() {
        // spent is negative, so adding it subtracts the spending.
        return allocated.plus(spent);
    }

    /** Fraction of the overall budget consumed; 0 when no budget is set. */
    public double consumedFraction() {
        if (allocated.isZero()) {
            return 0d;
        }
        return (double) spent.abs().minorUnits() / (double) allocated.minorUnits();
    }
}
