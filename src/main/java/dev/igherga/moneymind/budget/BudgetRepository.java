package dev.igherga.moneymind.budget;

import dev.igherga.moneymind.common.Money;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

/**
 * Reads and writes budgets as native SQL.
 *
 * <p>Two levels live here. The <b>overall monthly budget</b> is one row per
 * month in {@code monthly_budget}. A <b>category budget</b> is a {@code budget}
 * row holding the intent plus a {@code budget_period} row materialising the
 * concrete window — that split is what makes "I raised the food budget in June"
 * expressible at all.
 */
@Repository
public class BudgetRepository {

    private final JdbcTemplate jdbc;

    BudgetRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The overall budget in force for {@code month}: the row for that month if
     * there is one, otherwise the most recent earlier month's.
     *
     * <p>Carrying forward means the user sets the figure once instead of every
     * month. The returned record says which of the two happened, so the UI can
     * distinguish "you budgeted this" from "this is what you last budgeted".
     */
    public Optional<EffectiveBudget> effectiveMonthlyBudget(String currency, YearMonth month) {
        List<EffectiveBudget> found = jdbc.query("""
                SELECT period_month, amount_minor
                  FROM monthly_budget
                 WHERE currency = ?
                   AND period_month <= ?
                 ORDER BY period_month DESC
                 LIMIT 1
                """,
                (rs, row) -> new EffectiveBudget(
                        YearMonth.parse(rs.getString("period_month")),
                        Money.of(rs.getLong("amount_minor"), currency)),
                currency, month.toString());

        return found.stream().findFirst();
    }

    /** Sets the overall budget for one month, replacing any existing amount. */
    public void setMonthlyBudget(String currency, YearMonth month, long amountMinor) {
        jdbc.update("""
                INSERT INTO monthly_budget (currency, period_month, amount_minor)
                VALUES (?, ?, ?)
                ON CONFLICT (currency, period_month)
                DO UPDATE SET amount_minor = excluded.amount_minor
                """, currency, month.toString(), amountMinor);
    }

    public void clearMonthlyBudget(String currency, YearMonth month) {
        jdbc.update("DELETE FROM monthly_budget WHERE currency = ? AND period_month = ?",
                currency, month.toString());
    }

    /**
     * Every live expense category with its budget for the month, if any, and
     * what has been spent against it.
     *
     * <p>A {@code LEFT JOIN} onto the budget tables rather than an inner one:
     * the Budgets screen has to list categories that are <em>not</em> budgeted,
     * since that is precisely what the user is there to change.
     *
     * <p>Spend is matched to the category or any child of it, uses
     * {@code kind = 'EXPENSE'} so transfers cannot leak in, and sums signed
     * amounts so refunds reduce it.
     */
    public List<CategoryBudget> categoryBudgets(String currency, YearMonth month) {
        LocalDate startsOn = month.atDay(1);
        LocalDate endsOn = month.atEndOfMonth();
        return jdbc.query("""
                SELECT c.id                AS category_id,
                       c.name,
                       c.system_key,
                       bp.allocated_minor + bp.rollover_in_minor AS allocated_minor,
                       COALESCE((
                           SELECT SUM(t.amount_minor)
                             FROM txn t
                             LEFT JOIN category tc ON tc.id = t.category_id
                            WHERE t.kind = 'EXPENSE'
                              AND t.currency = ?
                              AND t.booked_on >= ?
                              AND t.booked_on <= ?
                              AND (t.category_id = c.id OR tc.parent_id = c.id)
                       ), 0)               AS spent_minor
                  FROM category c
                  LEFT JOIN budget b
                         ON b.category_id = c.id
                        AND b.currency = ?
                  LEFT JOIN budget_period bp
                         ON bp.budget_id = b.id
                        AND bp.starts_on = ?
                 WHERE c.kind = 'EXPENSE'
                   AND c.archived_at IS NULL
                   AND c.parent_id IS NULL
                 ORDER BY c.sort_order, c.name
                """,
                (rs, row) -> {
                    // Read as an object first: a missed LEFT JOIN yields NULL,
                    // which getLong would flatten to a real-looking zero and
                    // turn "not budgeted" into "budgeted nothing".
                    Object allocated = rs.getObject("allocated_minor");
                    return new CategoryBudget(
                            rs.getLong("category_id"),
                            rs.getString("name"),
                            rs.getString("system_key"),
                            allocated == null ? null : Money.of(((Number) allocated).longValue(), currency),
                            Money.of(rs.getLong("spent_minor"), currency));
                },
                currency, startsOn.toString(), endsOn.toString(), currency, startsOn.toString());
    }

    /**
     * Sets a category's budget for one month.
     *
     * <p>Upserts the intent row then the period row. The period window is the
     * calendar month, matching the overall budget — a category budget on a
     * different anchor day than the monthly total would make the two figures
     * describe different spans of time and never reconcile.
     */
    public void setCategoryBudget(String currency, YearMonth month, long categoryId, long amountMinor) {
        BudgetPeriodCalculator.Period period = BudgetPeriodCalculator.calendarMonth(month.atDay(1));
        long budgetId = findOrCreateBudget(currency, categoryId, amountMinor, period.startsOn());

        jdbc.update("""
                INSERT INTO budget_period (budget_id, starts_on, ends_on, allocated_minor, rollover_in_minor)
                VALUES (?, ?, ?, ?, 0)
                ON CONFLICT (budget_id, starts_on)
                DO UPDATE SET allocated_minor = excluded.allocated_minor,
                              ends_on         = excluded.ends_on
                """,
                budgetId, period.startsOn().toString(), period.endsOn().toString(), amountMinor);
    }

    /** Removes a category's budget for one month, leaving other months alone. */
    public void clearCategoryBudget(String currency, YearMonth month, long categoryId) {
        LocalDate startsOn = month.atDay(1);
        jdbc.update("""
                DELETE FROM budget_period
                 WHERE starts_on = ?
                   AND budget_id IN (SELECT id FROM budget WHERE category_id = ? AND currency = ?)
                """, startsOn.toString(), categoryId, currency);
    }

    private long findOrCreateBudget(String currency, long categoryId, long amountMinor, LocalDate activeFrom) {
        try {
            return jdbc.queryForObject(
                    "SELECT id FROM budget WHERE category_id = ? AND currency = ? ORDER BY id LIMIT 1",
                    Long.class, categoryId, currency);
        } catch (EmptyResultDataAccessException absent) {
            jdbc.update("""
                    INSERT INTO budget (category_id, default_amount_minor, period_type, anchor_day,
                                        rollover, currency, active_from)
                    VALUES (?, ?, 'MONTHLY', 1, 0, ?, ?)
                    """, categoryId, amountMinor, currency, activeFrom.toString());
            return jdbc.queryForObject("SELECT MAX(id) FROM budget", Long.class);
        }
    }

    /** The overall budget in force, and the month it was actually set for. */
    public record EffectiveBudget(YearMonth setFor, Money amount) {
    }
}
