package dev.igherga.moneymind.savings;

import java.time.LocalDate;

/**
 * A request to create or replace one savings goal.
 *
 * <p>{@code targetMinor} is a positive count of minor units, like every other
 * amount crossing the API — never a decimal. A goal has no direction to encode,
 * so unlike {@code TransactionCommand} there is no sign to carry: a negative
 * target is meaningless and is rejected rather than reinterpreted.
 *
 * <p>{@code targetDate} is optional and its absence is meaningful: a goal with
 * no deadline is a perfectly ordinary goal, and the pace rule declines to say
 * anything about it rather than inventing a date to measure against.
 *
 * @param accountId the account backing the goal. Required for
 *                  {@link FundingMode#ACCOUNT_BALANCE}, optional otherwise
 */
public record GoalCommand(
        String name,
        long targetMinor,
        LocalDate targetDate,
        Long accountId,
        FundingMode fundingMode) {
}
