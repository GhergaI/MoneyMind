package dev.igherga.moneymind.savings;

/**
 * How a savings goal knows what has been put towards it.
 *
 * <p>Money is fungible, so "saved 400 towards the car" is not a fact the ledger
 * holds on its own — it has to be derived, and there are exactly two honest ways
 * to do it:
 *
 * <ul>
 *   <li>{@link #ACCOUNT_BALANCE} — the goal mirrors one real account. The
 *       balance <em>is</em> the progress, so nothing can drift: moving money in
 *       or out of that account moves the goal with it. This needs a dedicated
 *       account, which is why it requires one.</li>
 *   <li>{@link #EARMARKED} — several goals share one account and each
 *       contribution is tagged against a goal. Progress is the sum of the tags,
 *       which is the only way to tell two goals apart inside one balance.</li>
 * </ul>
 */
public enum FundingMode {

    /** Progress is the linked account's balance. Requires an account. */
    ACCOUNT_BALANCE,

    /** Progress is the sum of contributions tagged against the goal. */
    EARMARKED
}
