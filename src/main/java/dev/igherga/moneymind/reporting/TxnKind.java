package dev.igherga.moneymind.reporting;

/**
 * Mirrors the {@code txn.kind} CHECK constraint.
 *
 * <p>This value is <b>stored</b>, never inferred from the sign of the amount. A
 * refund is a positive amount whose kind is still {@code EXPENSE}, and reading
 * it as income is the classic way to overstate a month's earnings.
 */
public enum TxnKind {
    INCOME,
    EXPENSE,
    TRANSFER
}
