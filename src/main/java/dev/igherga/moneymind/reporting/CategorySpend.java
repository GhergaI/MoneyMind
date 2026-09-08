package dev.igherga.moneymind.reporting;

import dev.igherga.moneymind.common.Money;

/**
 * Spending in one top-level category over a window.
 *
 * <p>{@code spent} is the signed sum, so it is normally negative and a refund
 * genuinely reduces it. Categories whose net lands at or above zero — fully
 * refunded — are dropped by the query rather than shown as negative spending.
 *
 * <p>Sub-categories are rolled up into their parent, so the chart shows one bar
 * per top-level category no matter how the tree is arranged underneath.
 */
public record CategorySpend(long categoryId, String name, String systemKey, Money spent) {
}
