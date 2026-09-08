package dev.igherga.moneymind.savings;

import dev.igherga.moneymind.common.Money;

import java.time.LocalDate;

/**
 * One savings goal as the Goals screen needs it: the stored intent plus the
 * derived progress.
 *
 * <p>The dashboard's {@code SavingsGoalProgress} deliberately carries only what
 * a read-only panel plots. Editing needs the two fields it leaves out — which
 * account backs the goal and how it is funded — because those are what the user
 * is there to change.
 *
 * <p>{@code saved} is derived, never stored, for the same reason spending is:
 * a saved figure kept beside the account balance could disagree with it, and
 * nothing would say which was right.
 */
public record SavingsGoalDetail(
        long id,
        String name,
        Money target,
        Money saved,
        LocalDate startedOn,
        LocalDate targetDate,
        Long accountId,
        FundingMode fundingMode) {

    /** Fraction of the target reached; 0 for a zero target rather than dividing by it. */
    public double reachedFraction() {
        if (target.minorUnits() == 0) {
            return 0d;
        }
        return (double) saved.minorUnits() / (double) target.minorUnits();
    }
}
