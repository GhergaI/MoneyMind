package dev.igherga.moneymind.web;

import dev.igherga.moneymind.reporting.LedgerEntry;
import dev.igherga.moneymind.reporting.ReportingQueries;
import dev.igherga.moneymind.reporting.TxnKind;
import dev.igherga.moneymind.transaction.TransactionCommand;
import dev.igherga.moneymind.transaction.TransactionService;
import dev.igherga.moneymind.transaction.TransferCommand;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * Phase 1's entry path: reading, adding, editing and removing ledger rows.
 *
 * <p>Amounts arrive as signed minor units, exactly as they are stored. The
 * request does not carry a currency: it is read from the account, so the two
 * can never contradict each other.
 */
@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    /** Enough rows for a month of entry without paging. */
    private static final int MONTH_LIMIT = 500;

    private final TransactionService transactions;
    private final ReportingQueries queries;
    private final String defaultCurrency;

    TransactionController(TransactionService transactions, ReportingQueries queries,
            @Value("${moneymind.default-currency}") String defaultCurrency) {
        this.transactions = transactions;
        this.queries = queries;
        this.defaultCurrency = defaultCurrency;
    }

    /** One month of activity, newest first. Defaults to the current month. */
    @GetMapping
    public TransactionPage list(@RequestParam(required = false) String month) {
        YearMonth target = month == null ? YearMonth.now() : YearMonth.parse(month);
        List<LedgerEntry> entries = queries.entriesBetween(
                defaultCurrency, target.atDay(1), target.plusMonths(1).atDay(1), MONTH_LIMIT);

        return new TransactionPage(
                defaultCurrency,
                target.toString(),
                entries.stream().map(TransactionController::line).toList());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Created create(@RequestBody TransactionRequest request) {
        return new Created(transactions.create(request.toCommand()));
    }

    @PutMapping("/{id}")
    public void update(@PathVariable long id, @RequestBody TransactionRequest request) {
        transactions.update(id, request.toCommand());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        transactions.delete(id);
    }

    /**
     * Transfers have their own route because they are not transactions: one
     * request writes a parent row and two opposing legs, which cannot be
     * expressed as a single ledger row.
     */
    @PostMapping("/transfer")
    @ResponseStatus(HttpStatus.CREATED)
    public Created transfer(@RequestBody TransferRequest request) {
        return new Created(transactions.transfer(new TransferCommand(
                request.fromAccountId(), request.toAccountId(), request.amountMinor(),
                LocalDate.parse(request.bookedOn()), request.note())));
    }

    private static EntryLine line(LedgerEntry entry) {
        return new EntryLine(
                entry.id(),
                entry.bookedOn().toString(),
                entry.description(),
                entry.kind().name(),
                entry.accountName(),
                entry.categoryName(),
                entry.amount().minorUnits());
    }

    public record TransactionPage(String currency, String month, List<EntryLine> entries) {
    }

    public record EntryLine(
            long id, String bookedOn, String description, String kind,
            String accountName, String categoryName, long amountMinor) {
    }

    public record Created(long id) {
    }

    /**
     * @param amountMinor signed, as stored — a positive amount against an
     *                    expense category is a refund and must stay expressible
     */
    public record TransactionRequest(
            long accountId,
            Long categoryId,
            String kind,
            long amountMinor,
            String bookedOn,
            String description,
            String notes) {

        TransactionCommand toCommand() {
            return new TransactionCommand(
                    accountId, categoryId, TxnKind.valueOf(kind), amountMinor,
                    LocalDate.parse(bookedOn), description, notes);
        }
    }

    /** @param amountMinor a positive magnitude; direction comes from the accounts */
    public record TransferRequest(
            long fromAccountId,
            long toAccountId,
            long amountMinor,
            String bookedOn,
            String note) {
    }
}
