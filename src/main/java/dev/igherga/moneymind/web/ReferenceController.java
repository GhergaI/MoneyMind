package dev.igherga.moneymind.web;

import dev.igherga.moneymind.reporting.AccountBalance;
import dev.igherga.moneymind.reporting.CategoryOption;
import dev.igherga.moneymind.reporting.ReportingQueries;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The lists an entry form needs to offer valid choices.
 *
 * <p>Categories carry their kind so the picker can be filtered to the direction
 * being entered — better to make an invalid pair unselectable than to reject it
 * after the user has typed everything.
 */
@RestController
@RequestMapping("/api")
public class ReferenceController {

    private final ReportingQueries queries;

    ReferenceController(ReportingQueries queries) {
        this.queries = queries;
    }

    @GetMapping("/categories")
    public List<CategoryLine> categories() {
        return queries.categoryOptions().stream()
                .map(ReferenceController::line)
                .toList();
    }

    @GetMapping("/accounts")
    public List<AccountLine> accounts() {
        return queries.accountBalances().stream()
                .map(ReferenceController::line)
                .toList();
    }

    private static CategoryLine line(CategoryOption option) {
        return new CategoryLine(option.id(), option.name(), option.systemKey(),
                option.kind().name());
    }

    private static AccountLine line(AccountBalance account) {
        return new AccountLine(account.id(), account.name(), account.type().name(),
                account.balance().currency(), account.balance().minorUnits());
    }

    public record CategoryLine(long id, String name, String systemKey, String kind) {
    }

    public record AccountLine(
            long id, String name, String type, String currency, long balanceMinor) {
    }
}
