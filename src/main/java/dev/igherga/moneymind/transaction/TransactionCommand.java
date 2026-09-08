package dev.igherga.moneymind.transaction;

import dev.igherga.moneymind.reporting.TxnKind;

import java.time.LocalDate;

/**
 * A request to create or replace one ledger row.
 *
 * <p><b>{@code amountMinor} is signed, as stored.</b> The API does not take a
 * magnitude and a direction and work out the sign itself, because the sign is
 * part of the meaning: a positive amount in an expense category is a refund,
 * and that has to be expressible. The UI collects a positive number plus a
 * direction and does the arithmetic before sending.
 *
 * <p>{@code kind} is likewise explicit and never inferred from the sign — see
 * {@link TxnKind}.
 */
public record TransactionCommand(
        long accountId,
        Long categoryId,
        TxnKind kind,
        long amountMinor,
        LocalDate bookedOn,
        String description,
        String notes) {
}
