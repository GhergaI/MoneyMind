package dev.igherga.moneymind.reporting;

import dev.igherga.moneymind.common.Money;

/**
 * One account's current balance.
 *
 * <p>The balance is {@code opening_balance_minor + SUM(txn.amount_minor)} over
 * <em>every</em> row belonging to the account, transfer legs included — a
 * transfer really does move both balances even though it is neither income nor
 * spending. See {@code TransferReportingTest}.
 *
 * <p>The amount is signed from the account's perspective, so a credit card
 * carries a negative balance meaning "you owe". Signs are flipped for display
 * only, never here.
 */
public record AccountBalance(long id, String name, AccountType type, Money balance) {

    /** Whether this account holds money you own rather than money you owe. */
    public boolean isAsset() {
        return type != AccountType.CREDIT_CARD;
    }
}
