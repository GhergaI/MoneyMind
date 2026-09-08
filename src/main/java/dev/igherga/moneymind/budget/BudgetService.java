package dev.igherga.moneymind.budget;

import dev.igherga.moneymind.common.Money;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

/**
 * Setting and reading budgets.
 *
 * <p>Note what this service does <b>not</b> offer: any way to write a "spent"
 * figure. Spending is always derived from {@code txn} rows, never stored
 * alongside the budget. A typed-in spend total would immediately disagree with
 * the account balances and the transaction list that are supposed to explain
 * it, and there would be no way to tell which of the two was lying.
 */
@Service
public class BudgetService {

    private final BudgetRepository budgets;
    private final Clock clock;
    private final String defaultCurrency;

    BudgetService(BudgetRepository budgets, Clock clock,
            @Value("${moneymind.default-currency}") String defaultCurrency) {
        this.budgets = budgets;
        this.clock = clock;
        this.defaultCurrency = Money.zero(defaultCurrency).currency();
    }

    @Transactional(readOnly = true)
    public MonthlyBudgetView view(YearMonth month) {
        String currency = defaultCurrency;
        Optional<BudgetRepository.EffectiveBudget> effective =
                budgets.effectiveMonthlyBudget(currency, month);

        List<CategoryBudget> categories = budgets.categoryBudgets(currency, month);

        // The overall spend is the sum of the per-category spends, which the
        // repository already computed — summing them here rather than issuing
        // another query keeps the donut's slices and its total consistent by
        // construction. Every category is the same currency, so Money.sum is safe.
        Money spent = Money.sum(currency, categories.stream().map(CategoryBudget::spent).toList());

        return new MonthlyBudgetView(
                month,
                currency,
                effective.map(BudgetRepository.EffectiveBudget::amount).orElse(Money.zero(currency)),
                effective.map(found -> !found.setFor().equals(month)).orElse(false),
                spent,
                categories);
    }

    @Transactional(readOnly = true)
    public MonthlyBudgetView currentView() {
        return view(YearMonth.from(LocalDate.now(clock)));
    }

    /** Sets the overall budget for a month; a zero amount clears it. */
    @Transactional
    public MonthlyBudgetView setMonthlyBudget(YearMonth month, long amountMinor) {
        if (amountMinor < 0) {
            throw new IllegalArgumentException("a monthly budget cannot be negative");
        }
        if (amountMinor == 0) {
            budgets.clearMonthlyBudget(defaultCurrency, month);
        } else {
            budgets.setMonthlyBudget(defaultCurrency, month, amountMinor);
        }
        return view(month);
    }

    /** Sets one category's budget for a month; a zero amount removes it. */
    @Transactional
    public MonthlyBudgetView setCategoryBudget(YearMonth month, long categoryId, long amountMinor) {
        if (amountMinor < 0) {
            throw new IllegalArgumentException("a category budget cannot be negative");
        }
        if (amountMinor == 0) {
            budgets.clearCategoryBudget(defaultCurrency, month, categoryId);
        } else {
            budgets.setCategoryBudget(defaultCurrency, month, categoryId, amountMinor);
        }
        return view(month);
    }
}
