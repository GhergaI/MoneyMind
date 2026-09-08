package dev.igherga.moneymind.budget;

import dev.igherga.moneymind.common.Money;

/**
 * A category's budget for one month, with what has been spent against it.
 *
 * <p>{@code allocated} is {@code null} when the category has no budget at all,
 * which is different from a budget of zero — "not budgeted" and "budgeted
 * nothing" are distinct answers and the UI shows them differently.
 *
 * <p>{@code spent} is the signed expense sum, so it is negative and a refund
 * reduces it.
 */
public record CategoryBudget(
        long categoryId,
        String name,
        String systemKey,
        Money allocated,
        Money spent) {

    public boolean isBudgeted() {
        return allocated != null;
    }
}
