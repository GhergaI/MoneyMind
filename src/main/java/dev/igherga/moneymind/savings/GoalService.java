package dev.igherga.moneymind.savings;

import dev.igherga.moneymind.common.Money;
import dev.igherga.moneymind.reporting.ReportingQueries;
import dev.igherga.moneymind.reporting.SavingsGoalProgress;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Creating, editing and archiving savings goals.
 *
 * <p>Note what this service does <b>not</b> offer: any way to write a "saved"
 * figure. Progress is derived — from the linked account's balance, or from the
 * contributions tagged against the goal — for the same reason spending is never
 * writable. A typed-in saved amount would sit next to an account balance that
 * disagreed with it, and nothing in the app could say which of the two was
 * lying. To change what has been saved, move money.
 *
 * <p>Goals are archived, never deleted. Reaching a goal is an event worth
 * keeping, and its contributions point at real ledger rows.
 */
@Service
public class GoalService {

    private final GoalRepository goals;
    private final ReportingQueries queries;
    private final Clock clock;
    private final String defaultCurrency;

    GoalService(GoalRepository goals, ReportingQueries queries, Clock clock,
            @Value("${moneymind.default-currency}") String defaultCurrency) {
        this.goals = goals;
        this.queries = queries;
        this.clock = clock;
        this.defaultCurrency = Money.zero(defaultCurrency).currency();
    }

    /** Every live goal with the progress the dashboard would show for it. */
    @Transactional(readOnly = true)
    public GoalsView view() {
        Map<Long, Money> saved = new HashMap<>();
        for (SavingsGoalProgress progress : queries.savingsGoals(defaultCurrency)) {
            saved.put(progress.id(), progress.saved());
        }

        List<SavingsGoalDetail> live = goals.live(defaultCurrency).stream()
                .map(row -> new SavingsGoalDetail(
                        row.id(),
                        row.name(),
                        Money.of(row.targetMinor(), row.currency()),
                        // A goal with nothing saved yet is absent from the
                        // progress projection rather than present as a zero.
                        saved.getOrDefault(row.id(), Money.zero(row.currency())),
                        row.startedOn(),
                        row.targetDate(),
                        row.accountId(),
                        row.fundingMode()))
                .toList();

        return new GoalsView(defaultCurrency, live);
    }

    /** Creates a goal, returning its id the way the transaction path does. */
    @Transactional
    public long create(GoalCommand command) {
        validate(command);

        // Goals are denominated in the reporting currency: the dashboard groups
        // every figure it shows by that one code, and a goal held in anything
        // else would not be comparable with the savings total beside it.
        String currency = defaultCurrency;

        return goals.insert(command.name().trim(), command.targetMinor(), currency,
                command.targetDate(), command.accountId(), command.fundingMode());
    }

    @Transactional
    public void update(long id, GoalCommand command) {
        validate(command);

        String currency = defaultCurrency;

        int updated = goals.update(id, command.name().trim(), command.targetMinor(), currency,
                command.targetDate(), command.accountId(), command.fundingMode());
        if (updated == 0) {
            throw new IllegalArgumentException("No savings goal with id " + id);
        }
    }

    /**
     * Archives a goal. Idempotent in effect but not silent: archiving something
     * that is already gone is a sign the caller is working from a stale screen,
     * which is worth saying out loud.
     */
    @Transactional
    public void archive(long id) {
        int archived = goals.archive(id, LocalDate.now(clock));
        if (archived == 0) {
            throw new IllegalArgumentException("No savings goal with id " + id);
        }
    }

    // --- validation ---------------------------------------------------------

    private void validate(GoalCommand command) {
        if (command.name() == null || command.name().isBlank()) {
            throw new IllegalArgumentException("A savings goal needs a name");
        }
        if (command.targetMinor() <= 0) {
            throw new IllegalArgumentException("A savings goal's target must be more than zero");
        }
        if (command.fundingMode() == null) {
            throw new IllegalArgumentException(
                    "A savings goal needs to say how it is funded: ACCOUNT_BALANCE or EARMARKED");
        }
        if (command.fundingMode() == FundingMode.ACCOUNT_BALANCE && command.accountId() == null) {
            throw new IllegalArgumentException(
                    "A goal that mirrors an account balance needs an account");
        }

        // Checked here rather than left to the foreign key: a rejected write
        // should arrive as a sentence, not as a constraint name.
        if (command.accountId() != null) {
            goals.account(command.accountId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "No account with id " + command.accountId()));
        }
    }
}
