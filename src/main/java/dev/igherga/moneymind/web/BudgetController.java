package dev.igherga.moneymind.web;

import dev.igherga.moneymind.budget.BudgetService;
import dev.igherga.moneymind.budget.CategoryBudget;
import dev.igherga.moneymind.budget.MonthlyBudgetView;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;
import java.util.List;

/**
 * Setting the monthly budget and the per-category budgets under it.
 *
 * <p>There is deliberately no way to write a spend figure here. Spending is
 * derived from the ledger, so the only way to change it is to record a
 * transaction — which is what keeps the budget, the balances and the
 * transaction list describing the same reality.
 */
@RestController
@RequestMapping("/api/budgets")
public class BudgetController {

    private final BudgetService budgets;

    BudgetController(BudgetService budgets) {
        this.budgets = budgets;
    }

    @GetMapping
    public BudgetResponse get(@RequestParam(required = false) String month) {
        return BudgetResponse.from(
                month == null ? budgets.currentView() : budgets.view(YearMonth.parse(month)));
    }

    /** Sets the overall monthly budget. Zero clears it. */
    @PutMapping("/monthly")
    public BudgetResponse setMonthly(@RequestBody AmountRequest request) {
        return BudgetResponse.from(
                budgets.setMonthlyBudget(YearMonth.parse(request.month()), request.amountMinor()));
    }

    /** Sets one category's budget for the month. Zero removes it. */
    @PutMapping("/category/{categoryId}")
    public BudgetResponse setCategory(@PathVariable long categoryId,
            @RequestBody AmountRequest request) {
        return BudgetResponse.from(budgets.setCategoryBudget(
                YearMonth.parse(request.month()), categoryId, request.amountMinor()));
    }

    public record AmountRequest(String month, long amountMinor) {
    }

    /**
     * @param inherited     the amount was carried forward from an earlier month
     *                      rather than set for this one
     * @param remainingMinor budget minus spending; negative means overspent
     */
    public record BudgetResponse(
            String currency,
            String month,
            long allocatedMinor,
            boolean allocated,
            boolean inherited,
            long spentMinor,
            long remainingMinor,
            List<CategoryLine> categories) {

        static BudgetResponse from(MonthlyBudgetView view) {
            return new BudgetResponse(
                    view.currency(),
                    view.month().toString(),
                    view.allocated().minorUnits(),
                    !view.allocated().isZero(),
                    view.inherited(),
                    view.spent().minorUnits(),
                    view.remaining().minorUnits(),
                    view.categories().stream().map(BudgetResponse::line).toList());
        }

        private static CategoryLine line(CategoryBudget category) {
            return new CategoryLine(
                    category.categoryId(),
                    category.name(),
                    category.systemKey(),
                    // null rather than 0: "not budgeted" and "budgeted nothing"
                    // are different answers and the UI shows them differently.
                    category.isBudgeted() ? category.allocated().minorUnits() : null,
                    category.spent().minorUnits());
        }
    }

    public record CategoryLine(
            long id, String name, String systemKey, Long allocatedMinor, long spentMinor) {
    }
}
