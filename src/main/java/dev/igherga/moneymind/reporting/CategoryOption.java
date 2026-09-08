package dev.igherga.moneymind.reporting;

/**
 * A category as an entry form offers it.
 *
 * <p>{@code kind} travels with it so the UI can filter the picker to categories
 * that match the direction being entered, rather than letting the user choose
 * an invalid pair and then rejecting it.
 */
public record CategoryOption(long id, String name, String systemKey, TxnKind kind) {
}
