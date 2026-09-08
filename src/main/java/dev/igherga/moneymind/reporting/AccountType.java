package dev.igherga.moneymind.reporting;

/**
 * Mirrors the {@code account.type} CHECK constraint in the baseline schema.
 * Kept as an enum so a typo in a query becomes a startup-time failure rather
 * than a silently empty total.
 */
public enum AccountType {
    CURRENT,
    SAVINGS,
    CASH,
    CREDIT_CARD,
    INVESTMENT
}
