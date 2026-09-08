package dev.igherga.moneymind.transaction;

/**
 * A write was rejected because it would have broken a ledger rule.
 *
 * <p>Distinct from the {@link org.springframework.dao.DataAccessException} the
 * database triggers raise: these are the checks made <em>before</em> the write,
 * so the user gets "Groceries is an expense category" rather than
 * {@code SQLITE_CONSTRAINT}. The triggers remain the backstop — this class does
 * not replace them, because raw SQL and imports bypass this layer entirely.
 */
public class TransactionValidationException extends RuntimeException {

    public TransactionValidationException(String message) {
        super(message);
    }
}
