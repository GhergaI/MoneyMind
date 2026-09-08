package dev.igherga.moneymind.insight.narrate;

import dev.igherga.moneymind.insight.Insight;

/**
 * Turns an already-computed {@link Insight} into the sentence the user reads.
 *
 * <p>The contract is narrow on purpose: a phraser receives facts that are
 * already final and may only change wording. It is never given the ledger, it
 * never computes anything, and its output is never parsed back into a number.
 * That boundary is what keeps the app fully functional with
 * {@code moneymind.ai.enabled=false}, which is the default and the only mode
 * the numbers depend on.
 */
public interface InsightPhraser {

    /**
     * Returns the insight with its message possibly reworded. Implementations
     * must return the input unchanged rather than throw if they cannot phrase
     * it — a failed rewording is a cosmetic problem, not a broken dashboard.
     */
    Insight phrase(Insight insight);
}
