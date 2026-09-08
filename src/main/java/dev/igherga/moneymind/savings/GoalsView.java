package dev.igherga.moneymind.savings;

import java.util.List;

/**
 * The Goals screen in one read: every live goal, plus the currency they are all
 * denominated in.
 *
 * <p>The currency is named once here rather than repeated on every goal, the
 * same way the dashboard and budget payloads do it — one code for the whole
 * response is what makes the amounts safe to compare with each other.
 */
public record GoalsView(String currency, List<SavingsGoalDetail> goals) {
}
