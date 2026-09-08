package dev.igherga.moneymind.transaction;

import java.time.LocalDate;

/**
 * A request to move money between two of your own accounts.
 *
 * <p>Separate from {@link TransactionCommand} because a transfer is not a
 * transaction — it is a {@code transfer} row plus exactly two {@code txn} legs,
 * and the schema will not let a single {@code TRANSFER} row exist on its own.
 * Exposing it as "two transactions" would make it impossible to create one
 * through the API and would invite half-transfers that corrupt every balance.
 *
 * <p>{@code amountMinor} is a positive magnitude here: the direction is carried
 * by which account is {@code from} and which is {@code to}, so a sign would be
 * redundant and ambiguous.
 */
public record TransferCommand(
        long fromAccountId,
        long toAccountId,
        long amountMinor,
        LocalDate bookedOn,
        String note) {
}
