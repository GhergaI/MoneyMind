package dev.igherga.moneymind.transaction;

import dev.igherga.moneymind.reporting.TxnKind;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Locale;

/**
 * Writing to the ledger — Phase 1's entry path.
 *
 * <p>Every rule is enforced here <em>before</em> the insert, so the user sees a
 * sentence instead of a constraint name. The database triggers stay in place as
 * the real guarantee, since imports and raw SQL never come through this class.
 *
 * <p>The rules, and why each one exists:
 *
 * <ul>
 *   <li><b>A transaction's currency is its account's.</b> Not a parameter at
 *       all: it is read from the account, so the two can never disagree.</li>
 *   <li><b>The category's kind must match the transaction's.</b> Booking a
 *       salary against Groceries would put income into a spending category and
 *       corrupt every budget that category feeds.</li>
 *   <li><b>Only transfers may omit a category, and they must omit it.</b> A
 *       categorised transfer leg would consume a budget it has no business
 *       touching.</li>
 *   <li><b>Transfers are never written through the transaction path.</b> They
 *       need two legs and a parent row; {@link #transfer} is the only way in.</li>
 *   <li><b>A transfer's two accounts must differ and share a currency.</b>
 *       Cross-currency movement needs an FX rate and a story about which side
 *       the loss lands on; until that exists, refusing is the honest answer
 *       rather than silently inventing a rate of 1.</li>
 * </ul>
 */
@Service
public class TransactionService {

    private final JdbcTemplate jdbc;

    TransactionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public long create(TransactionCommand command) {
        validate(command);
        String currency = currencyOf(command.accountId());

        jdbc.update("""
                INSERT INTO txn (account_id, category_id, transfer_id, kind, amount_minor,
                                 currency, booked_on, description, notes, source)
                VALUES (?, ?, NULL, ?, ?, ?, ?, ?, ?, 'MANUAL')
                """,
                command.accountId(), command.categoryId(), command.kind().name(),
                command.amountMinor(), currency, command.bookedOn().toString(),
                blankToNull(command.description()), blankToNull(command.notes()));

        return jdbc.queryForObject("SELECT MAX(id) FROM txn", Long.class);
    }

    /**
     * Replaces one transaction. Refuses to touch a transfer leg: editing one
     * side of a transfer in isolation would leave the pair unbalanced, and the
     * two legs must always be equal and opposite.
     */
    @Transactional
    public void update(long id, TransactionCommand command) {
        requireNotATransferLeg(id, "edited");
        validate(command);
        String currency = currencyOf(command.accountId());

        int updated = jdbc.update("""
                UPDATE txn
                   SET account_id  = ?,
                       category_id = ?,
                       kind        = ?,
                       amount_minor = ?,
                       currency    = ?,
                       booked_on   = ?,
                       description = ?,
                       notes       = ?,
                       manually_edited = 1
                 WHERE id = ?
                   AND transfer_id IS NULL
                """,
                command.accountId(), command.categoryId(), command.kind().name(),
                command.amountMinor(), currency, command.bookedOn().toString(),
                blankToNull(command.description()), blankToNull(command.notes()), id);

        if (updated == 0) {
            throw new TransactionValidationException("No transaction with id " + id);
        }
    }

    /**
     * Deletes a transaction, or a whole transfer when given either of its legs.
     *
     * <p>Deleting one leg of a transfer is never right: it would leave money
     * apparently created or destroyed. So a leg deletes its partner and the
     * parent row with it.
     */
    @Transactional
    public void delete(long id) {
        Long transferId = transferIdOf(id);

        if (transferId != null) {
            jdbc.update("DELETE FROM txn WHERE transfer_id = ?", transferId);
            jdbc.update("DELETE FROM transfer WHERE id = ?", transferId);
            return;
        }

        int deleted = jdbc.update("DELETE FROM txn WHERE id = ?", id);
        if (deleted == 0) {
            throw new TransactionValidationException("No transaction with id " + id);
        }
    }

    /** Records a transfer as a parent row plus exactly two opposing legs. */
    @Transactional
    public long transfer(TransferCommand command) {
        if (command.amountMinor() <= 0) {
            throw new TransactionValidationException("A transfer amount must be positive");
        }
        if (command.fromAccountId() == command.toAccountId()) {
            throw new TransactionValidationException("A transfer needs two different accounts");
        }
        if (command.bookedOn() == null) {
            throw new TransactionValidationException("A transfer needs a date");
        }

        String fromCurrency = currencyOf(command.fromAccountId());
        String toCurrency = currencyOf(command.toAccountId());
        if (!fromCurrency.equals(toCurrency)) {
            throw new TransactionValidationException(
                    "Transfers between " + fromCurrency + " and " + toCurrency
                            + " accounts need an exchange rate, which is not supported yet");
        }

        jdbc.update("INSERT INTO transfer (note, status) VALUES (?, 'COMPLETE')",
                blankToNull(command.note()));
        long transferId = jdbc.queryForObject("SELECT MAX(id) FROM transfer", Long.class);

        insertLeg(command.fromAccountId(), transferId, -command.amountMinor(),
                fromCurrency, command.bookedOn(), command.note());
        insertLeg(command.toAccountId(), transferId, command.amountMinor(),
                toCurrency, command.bookedOn(), command.note());

        return transferId;
    }

    private void insertLeg(long accountId, long transferId, long amountMinor,
            String currency, LocalDate bookedOn, String note) {
        jdbc.update("""
                INSERT INTO txn (account_id, category_id, transfer_id, kind, amount_minor,
                                 currency, booked_on, description, source)
                VALUES (?, NULL, ?, 'TRANSFER', ?, ?, ?, ?, 'MANUAL')
                """,
                accountId, transferId, amountMinor, currency, bookedOn.toString(),
                blankToNull(note));
    }

    // --- validation ---------------------------------------------------------

    private void validate(TransactionCommand command) {
        if (command.kind() == null) {
            throw new TransactionValidationException("A transaction needs a kind");
        }
        if (command.kind() == TxnKind.TRANSFER) {
            throw new TransactionValidationException(
                    "Record a transfer with the transfer endpoint — it needs two legs");
        }
        if (command.bookedOn() == null) {
            throw new TransactionValidationException("A transaction needs a date");
        }
        if (command.amountMinor() == 0) {
            throw new TransactionValidationException("A transaction needs a non-zero amount");
        }
        if (command.categoryId() == null) {
            throw new TransactionValidationException("A transaction needs a category");
        }

        String categoryKind = categoryKind(command.categoryId());
        if (!categoryKind.equals(command.kind().name())) {
            throw new TransactionValidationException(
                    categoryName(command.categoryId()) + " is "
                            + article(categoryKind) + " " + categoryKind.toLowerCase(Locale.ROOT)
                            + " category, so it cannot hold "
                            + article(command.kind().name()) + " "
                            + command.kind().name().toLowerCase(Locale.ROOT) + " transaction");
        }
    }

    private void requireNotATransferLeg(long id, String verb) {
        if (transferIdOf(id) != null) {
            throw new TransactionValidationException(
                    "A transfer leg cannot be " + verb + " on its own; delete the transfer instead");
        }
    }

    /**
     * The transfer this row belongs to, or {@code null} if it is an ordinary
     * transaction or does not exist.
     *
     * <p>Read through {@link java.sql.ResultSet#getLong} plus a null check
     * rather than casting {@code getObject}: sqlite-jdbc hands back an
     * {@code Integer} for any INTEGER that fits in one, so the obvious
     * {@code (Long) getObject(...)} throws {@link ClassCastException} on real
     * data while looking perfectly correct.
     */
    private Long transferIdOf(long id) {
        return jdbc.query(
                "SELECT transfer_id FROM txn WHERE id = ?",
                rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    long value = rs.getLong("transfer_id");
                    return rs.wasNull() ? null : value;
                },
                id);
    }

    private String currencyOf(long accountId) {
        try {
            return jdbc.queryForObject(
                    "SELECT currency FROM account WHERE id = ? AND archived_at IS NULL",
                    String.class, accountId);
        } catch (EmptyResultDataAccessException absent) {
            throw new TransactionValidationException("No account with id " + accountId);
        }
    }

    private String categoryKind(long categoryId) {
        try {
            return jdbc.queryForObject(
                    "SELECT kind FROM category WHERE id = ? AND archived_at IS NULL",
                    String.class, categoryId);
        } catch (EmptyResultDataAccessException absent) {
            throw new TransactionValidationException("No category with id " + categoryId);
        }
    }

    private String categoryName(long categoryId) {
        return jdbc.queryForObject("SELECT name FROM category WHERE id = ?", String.class, categoryId);
    }

    private static String article(String word) {
        return "AEIOU".indexOf(word.charAt(0)) >= 0 ? "an" : "a";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
