package dev.igherga.moneymind.reporting;

import dev.igherga.moneymind.common.Money;

import java.time.LocalDate;

/**
 * A row of the ledger as the recent-activity list shows it.
 *
 * <p>Transfer legs are included — they are real ledger events that moved a
 * balance — and are identifiable by {@code kind}, so the UI can label them
 * rather than let them read as income or spending. They never carry a category.
 */
public record LedgerEntry(
        long id,
        LocalDate bookedOn,
        String description,
        TxnKind kind,
        String accountName,
        String categoryName,
        Money amount) {
}
