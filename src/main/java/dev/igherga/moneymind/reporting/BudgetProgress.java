package dev.igherga.moneymind.reporting;

import dev.igherga.moneymind.common.Money;

import java.time.LocalDate;

/**
 * One budget's consumption within the period that currently contains today.
 *
 * <p>{@code allocated} is the period's own allocation plus any rollover carried
 * in, which is why the period rows are materialised rather than derived: "I
 * raised the groceries budget in June" is not expressible from the budget alone.
 *
 * <p>{@code spent} is the signed expense sum and is therefore negative. It is
 * exposed as-is; {@link #spentAbsolute()} is the presentation-side flip.
 */
public record BudgetProgress(
        long budgetId,
        String categoryName,
        LocalDate startsOn,
        LocalDate endsOn,
        Money allocated,
        Money spent) {

    /** Spend as a positive magnitude, for display and for the ratio below. */
    public Money spentAbsolute() {
        return spent.abs();
    }

    /**
     * Fraction of the allocation consumed, where 1.0 means exactly on budget.
     * Returns 0 for a zero allocation rather than dividing by zero — the UI
     * shows such a budget as unset instead of infinitely overspent.
     */
    public double consumedFraction() {
        if (allocated.minorUnits() == 0) {
            return 0d;
        }
        return (double) spentAbsolute().minorUnits() / (double) Math.abs(allocated.minorUnits());
    }
}
