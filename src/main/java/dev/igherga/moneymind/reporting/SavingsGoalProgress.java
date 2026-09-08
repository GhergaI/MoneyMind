package dev.igherga.moneymind.reporting;

import dev.igherga.moneymind.common.Money;

import java.time.LocalDate;

/**
 * How far one savings goal has come.
 *
 * <p>Money is fungible, so a goal is funded one of two ways and they compute
 * differently: {@code ACCOUNT_BALANCE} mirrors a real account's balance, while
 * {@code EARMARKED} sums the contributions tagged against the goal over a
 * shared account. The query resolves both into {@code saved}.
 */
public record SavingsGoalProgress(
        long id,
        String name,
        Money target,
        Money saved,
        LocalDate startedOn,
        LocalDate targetDate) {

    /**
     * Fraction of the target reached, clamped at nothing — a goal can legitimately
     * exceed 100%. Returns 0 for a zero target rather than dividing by zero.
     */
    public double reachedFraction() {
        if (target.minorUnits() == 0) {
            return 0d;
        }
        return (double) saved.minorUnits() / (double) target.minorUnits();
    }
}
