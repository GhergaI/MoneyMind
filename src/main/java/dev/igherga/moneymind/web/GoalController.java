package dev.igherga.moneymind.web;

import dev.igherga.moneymind.savings.FundingMode;
import dev.igherga.moneymind.savings.GoalCommand;
import dev.igherga.moneymind.savings.GoalService;
import dev.igherga.moneymind.savings.GoalsView;
import dev.igherga.moneymind.savings.SavingsGoalDetail;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Managing savings goals.
 *
 * <p>Every write answers with the whole list rather than the one row it
 * touched: the screen shows progress figures derived from the ledger, and
 * handing back a single goal would leave the caller to guess whether the rest
 * of the page had moved with it.
 *
 * <p>There is deliberately no way to write a saved amount. Progress comes from
 * the account balance or from tagged contributions — moving money is the only
 * way to change it.
 */
@RestController
@RequestMapping("/api/goals")
public class GoalController {

    private final GoalService goals;

    GoalController(GoalService goals) {
        this.goals = goals;
    }

    @GetMapping
    public GoalsResponse list() {
        return GoalsResponse.from(goals.view());
    }

    @PostMapping
    public GoalsResponse create(@RequestBody GoalRequest request) {
        goals.create(request.toCommand());
        return GoalsResponse.from(goals.view());
    }

    @PutMapping("/{id}")
    public GoalsResponse update(@PathVariable long id, @RequestBody GoalRequest request) {
        goals.update(id, request.toCommand());
        return GoalsResponse.from(goals.view());
    }

    /**
     * Archives a goal. Not a delete: the goal's contributions point at real
     * transactions, and a reached goal is part of the record.
     */
    @DeleteMapping("/{id}")
    public GoalsResponse archive(@PathVariable long id) {
        goals.archive(id);
        return GoalsResponse.from(goals.view());
    }

    /**
     * @param targetMinor  a positive count of minor units, never a decimal
     * @param targetDate   ISO date, or absent for a goal with no deadline
     * @param accountId    required when {@code fundingMode} is ACCOUNT_BALANCE
     * @param fundingMode  {@code ACCOUNT_BALANCE} or {@code EARMARKED}
     */
    public record GoalRequest(
            String name,
            long targetMinor,
            String targetDate,
            Long accountId,
            String fundingMode) {

        GoalCommand toCommand() {
            return new GoalCommand(
                    name,
                    targetMinor,
                    targetDate == null || targetDate.isBlank() ? null : LocalDate.parse(targetDate),
                    accountId,
                    parseFundingMode(fundingMode));
        }

        /**
         * Reported as a sentence rather than letting {@code valueOf} throw an
         * {@code IllegalArgumentException} naming the enum constant, which is a
         * fact about our source code and not about the request.
         */
        private static FundingMode parseFundingMode(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            for (FundingMode mode : FundingMode.values()) {
                if (mode.name().equalsIgnoreCase(value)) {
                    return mode;
                }
            }
            throw new IllegalArgumentException(
                    "A goal is funded either ACCOUNT_BALANCE or EARMARKED, not " + value);
        }
    }

    public record GoalsResponse(String currency, List<GoalLine> goals) {

        static GoalsResponse from(GoalsView view) {
            return new GoalsResponse(
                    view.currency(),
                    view.goals().stream().map(GoalsResponse::line).toList());
        }

        private static GoalLine line(SavingsGoalDetail goal) {
            return new GoalLine(
                    goal.id(),
                    goal.name(),
                    goal.target().minorUnits(),
                    goal.saved().minorUnits(),
                    goal.startedOn().toString(),
                    goal.targetDate() == null ? null : goal.targetDate().toString(),
                    goal.accountId(),
                    goal.fundingMode().name());
        }
    }

    /**
     * @param savedMinor derived from the ledger, so read-only — the API accepts
     *                   no such field on the way in
     */
    public record GoalLine(
            long id,
            String name,
            long targetMinor,
            long savedMinor,
            String startedOn,
            String targetDate,
            Long accountId,
            String fundingMode) {
    }
}
