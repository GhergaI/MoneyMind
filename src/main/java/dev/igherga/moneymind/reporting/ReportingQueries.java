package dev.igherga.moneymind.reporting;

import dev.igherga.moneymind.common.Money;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Every read the dashboard performs, as native SQL projected straight into the
 * records in this package. No entities: these are reports, and mapping them
 * through JPA would load object graphs nobody needs and hide the aggregation.
 *
 * <p><b>The two rules every query here obeys.</b> Balances use <em>every</em>
 * row, transfer legs included. Income and expense are selected by the stored
 * {@code kind}, which excludes {@code TRANSFER} by construction and never
 * classifies by the sign of the amount — a refund is a positive
 * {@code EXPENSE}, not income. Sums are always {@code SUM(amount_minor)} and
 * never {@code SUM(ABS(...))}, so a refund reduces spend. All of this is pinned
 * by {@code TransferReportingTest}.
 *
 * <p>Every method takes a currency and filters on it. Nothing here ever sums
 * across currencies; the caller decides which currency it is reporting on and
 * is told which others exist.
 *
 * <p>Dates are ISO-8601 {@code TEXT} in SQLite, so lexicographic comparison is
 * chronological comparison and plain {@code >=} / {@code <} range predicates
 * work without any date function. Ranges are consistently half-open
 * {@code [from, toExclusive)} to avoid the classic end-of-month off-by-one.
 */
@Repository
public class ReportingQueries {

    private final JdbcTemplate jdbc;

    ReportingQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Balance of every live account, in account order.
     *
     * <p>{@code LEFT JOIN} so an account with no transactions still reports its
     * opening balance instead of vanishing from the list.
     */
    public List<AccountBalance> accountBalances() {
        return jdbc.query("""
                SELECT a.id,
                       a.name,
                       a.type,
                       a.currency,
                       a.opening_balance_minor + COALESCE(SUM(t.amount_minor), 0) AS balance_minor
                  FROM account a
                  LEFT JOIN txn t ON t.account_id = a.id
                 WHERE a.archived_at IS NULL
                 GROUP BY a.id, a.name, a.type, a.currency, a.opening_balance_minor
                 ORDER BY a.sort_order, a.name
                """, accountBalanceMapper());
    }

    /** Currency codes that hold at least one live account, in order of use. */
    public List<String> activeCurrencies() {
        return jdbc.queryForList("""
                SELECT currency
                  FROM account
                 WHERE archived_at IS NULL
                 GROUP BY currency
                 ORDER BY COUNT(*) DESC, currency
                """, String.class);
    }

    /**
     * Signed income total over {@code [from, toExclusive)}.
     *
     * <p>Selected by {@code kind = 'INCOME'}: transfers are excluded because
     * their kind is {@code TRANSFER}, not because of any sign test.
     */
    public Money incomeBetween(String currency, LocalDate from, LocalDate toExclusive) {
        return kindTotal("INCOME", currency, from, toExclusive);
    }

    /**
     * Signed expense total over {@code [from, toExclusive)}. Negative under
     * normal conditions; a month of net refunds would legitimately be positive.
     */
    public Money expenseBetween(String currency, LocalDate from, LocalDate toExclusive) {
        return kindTotal("EXPENSE", currency, from, toExclusive);
    }

    private Money kindTotal(String kind, String currency, LocalDate from, LocalDate toExclusive) {
        Long total = jdbc.queryForObject("""
                SELECT COALESCE(SUM(amount_minor), 0)
                  FROM txn
                 WHERE kind = ?
                   AND currency = ?
                   AND booked_on >= ?
                   AND booked_on <  ?
                """, Long.class, kind, currency, from.toString(), toExclusive.toString());
        return Money.of(total == null ? 0L : total, currency);
    }

    /**
     * Spending per top-level category over the window, biggest spend first.
     *
     * <p>Sub-categories roll up into their parent via
     * {@code COALESCE(parent.id, category.id)}; the tree is at most two deep, so
     * one join is enough. Uncategorised spending is grouped under a synthetic
     * id of 0 so it is visible rather than silently dropped.
     *
     * <p>{@code HAVING SUM(...) < 0} removes categories that netted out to zero
     * or positive after refunds — showing "Shopping: +40.00" in a spending
     * chart would be nonsense.
     */
    public List<CategorySpend> spendingByCategory(String currency, LocalDate from, LocalDate toExclusive) {
        return jdbc.query("""
                SELECT COALESCE(p.id, c.id, 0)                  AS category_id,
                       COALESCE(p.name, c.name, 'Uncategorised') AS name,
                       COALESCE(p.system_key, c.system_key)      AS system_key,
                       SUM(t.amount_minor)                       AS spent_minor
                  FROM txn t
                  LEFT JOIN category c ON c.id = t.category_id
                  LEFT JOIN category p ON p.id = c.parent_id
                 WHERE t.kind = 'EXPENSE'
                   AND t.currency = ?
                   AND t.booked_on >= ?
                   AND t.booked_on <  ?
                 GROUP BY COALESCE(p.id, c.id, 0),
                          COALESCE(p.name, c.name, 'Uncategorised'),
                          COALESCE(p.system_key, c.system_key)
                HAVING SUM(t.amount_minor) < 0
                 ORDER BY SUM(t.amount_minor) ASC
                """,
                (rs, row) -> new CategorySpend(
                        rs.getLong("category_id"),
                        rs.getString("name"),
                        rs.getString("system_key"),
                        Money.of(rs.getLong("spent_minor"), currency)),
                currency, from.toString(), toExclusive.toString());
    }

    /**
     * Monthly spending totals for the {@code months} calendar months ending with
     * {@code lastMonth} inclusive.
     *
     * <p>Months with no activity are absent from the result set, so they are
     * filled in here as zero — a gap in a trend chart would otherwise read as
     * "unknown" when it actually means "nothing spent".
     */
    public List<MonthSpend> spendingTrend(String currency, YearMonth lastMonth, int months) {
        YearMonth firstMonth = lastMonth.minusMonths(months - 1L);
        Map<YearMonth, Long> totals = new HashMap<>();
        jdbc.query("""
                SELECT substr(booked_on, 1, 7) AS month,
                       SUM(amount_minor)       AS spent_minor
                  FROM txn
                 WHERE kind = 'EXPENSE'
                   AND currency = ?
                   AND booked_on >= ?
                   AND booked_on <  ?
                 GROUP BY substr(booked_on, 1, 7)
                """,
                rs -> {
                    totals.put(YearMonth.parse(rs.getString("month")), rs.getLong("spent_minor"));
                },
                currency, firstMonth.atDay(1).toString(), lastMonth.plusMonths(1).atDay(1).toString());

        List<MonthSpend> trend = new ArrayList<>(months);
        for (int i = 0; i < months; i++) {
            YearMonth month = firstMonth.plusMonths(i);
            trend.add(new MonthSpend(month, Money.of(totals.getOrDefault(month, 0L), currency)));
        }
        return List.copyOf(trend);
    }

    /**
     * Budgets whose current period contains {@code on}.
     *
     * <p>The allocation includes {@code rollover_in_minor}, which is stored at
     * period close precisely so this query does not have to recurse over all
     * history to know what was carried forward.
     *
     * <p>Actuals are matched to the budget's category <em>or any child of it</em>,
     * so a budget set on a parent covers its sub-categories. The window is the
     * period's own inclusive {@code starts_on..ends_on}.
     */
    public List<BudgetProgress> budgetProgress(String currency, LocalDate on) {
        return jdbc.query("""
                SELECT b.id            AS budget_id,
                       c.name          AS category_name,
                       bp.starts_on,
                       bp.ends_on,
                       bp.allocated_minor + bp.rollover_in_minor AS allocated_minor,
                       COALESCE((
                           SELECT SUM(t.amount_minor)
                             FROM txn t
                             LEFT JOIN category tc ON tc.id = t.category_id
                            WHERE t.kind = 'EXPENSE'
                              AND t.currency = b.currency
                              AND t.booked_on >= bp.starts_on
                              AND t.booked_on <= bp.ends_on
                              AND (t.category_id = b.category_id OR tc.parent_id = b.category_id)
                       ), 0)           AS spent_minor
                  FROM budget_period bp
                  JOIN budget   b ON b.id = bp.budget_id
                  JOIN category c ON c.id = b.category_id
                 WHERE b.currency = ?
                   AND ? BETWEEN bp.starts_on AND bp.ends_on
                   AND b.active_from <= ?
                   AND (b.active_to IS NULL OR b.active_to >= ?)
                 ORDER BY c.sort_order, c.name
                """,
                (rs, row) -> new BudgetProgress(
                        rs.getLong("budget_id"),
                        rs.getString("category_name"),
                        LocalDate.parse(rs.getString("starts_on")),
                        LocalDate.parse(rs.getString("ends_on")),
                        Money.of(rs.getLong("allocated_minor"), currency),
                        Money.of(rs.getLong("spent_minor"), currency)),
                currency, on.toString(), on.toString(), on.toString());
    }

    /**
     * The most recent ledger rows, newest first.
     *
     * <p>Ordered by {@code booked_on} then {@code id} so same-day entries have a
     * stable order rather than whatever the file layout happens to yield.
     * Transfer legs are included and carry their kind.
     */
    public List<LedgerEntry> recentEntries(String currency, int limit) {
        return jdbc.query("""
                SELECT t.id,
                       t.booked_on,
                       t.description,
                       t.kind,
                       t.amount_minor,
                       a.name AS account_name,
                       c.name AS category_name
                  FROM txn t
                  JOIN account  a ON a.id = t.account_id
                  LEFT JOIN category c ON c.id = t.category_id
                 WHERE t.currency = ?
                 ORDER BY t.booked_on DESC, t.id DESC
                 LIMIT ?
                """,
                (rs, row) -> new LedgerEntry(
                        rs.getLong("id"),
                        LocalDate.parse(rs.getString("booked_on")),
                        rs.getString("description"),
                        TxnKind.valueOf(rs.getString("kind")),
                        rs.getString("account_name"),
                        rs.getString("category_name"),
                        Money.of(rs.getLong("amount_minor"), currency)),
                currency, limit);
    }

    /**
     * Ledger rows booked within a window, newest first.
     *
     * <p>Powers the transactions screen. Transfer legs are included: hiding
     * them would leave the user unable to see or delete a transfer they had
     * just recorded.
     */
    public List<LedgerEntry> entriesBetween(String currency, LocalDate from, LocalDate toExclusive,
            int limit) {
        return jdbc.query("""
                SELECT t.id,
                       t.booked_on,
                       t.description,
                       t.kind,
                       t.amount_minor,
                       a.name AS account_name,
                       c.name AS category_name
                  FROM txn t
                  JOIN account  a ON a.id = t.account_id
                  LEFT JOIN category c ON c.id = t.category_id
                 WHERE t.currency = ?
                   AND t.booked_on >= ?
                   AND t.booked_on <  ?
                 ORDER BY t.booked_on DESC, t.id DESC
                 LIMIT ?
                """,
                (rs, row) -> new LedgerEntry(
                        rs.getLong("id"),
                        LocalDate.parse(rs.getString("booked_on")),
                        rs.getString("description"),
                        TxnKind.valueOf(rs.getString("kind")),
                        rs.getString("account_name"),
                        rs.getString("category_name"),
                        Money.of(rs.getLong("amount_minor"), currency)),
                currency, from.toString(), toExclusive.toString(), limit);
    }

    /**
     * Categories an entry form may offer: live, and top-level only.
     *
     * <p>Sub-categories are excluded because nothing in the UI creates them
     * yet, and offering a two-level picker for a tree that is always flat would
     * be needless friction.
     */
    public List<CategoryOption> categoryOptions() {
        return jdbc.query("""
                SELECT id, name, system_key, kind
                  FROM category
                 WHERE archived_at IS NULL
                   AND parent_id IS NULL
                 ORDER BY kind DESC, sort_order, name
                """,
                (rs, row) -> new CategoryOption(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("system_key"),
                        TxnKind.valueOf(rs.getString("kind"))));
    }

    /**
     * Live savings goals with their progress.
     *
     * <p>The {@code CASE} resolves the two funding modes: an account-backed goal
     * reads the account's real balance, an earmarked goal sums the contributions
     * tagged to it. Doing this in SQL keeps a goal from double-counting money
     * that is only sitting in a shared account.
     */
    public List<SavingsGoalProgress> savingsGoals(String currency) {
        return jdbc.query("""
                SELECT g.id,
                       g.name,
                       g.target_minor,
                       g.target_date,
                       date(g.created_at) AS started_on,
                       CASE g.funding_mode
                           WHEN 'ACCOUNT_BALANCE' THEN (
                               SELECT a.opening_balance_minor + COALESCE(SUM(t.amount_minor), 0)
                                 FROM account a
                                 LEFT JOIN txn t ON t.account_id = a.id
                                WHERE a.id = g.account_id)
                           ELSE COALESCE((
                               SELECT SUM(sc.amount_minor)
                                 FROM savings_contribution sc
                                WHERE sc.goal_id = g.id), 0)
                       END AS saved_minor
                  FROM savings_goal g
                 WHERE g.archived_at IS NULL
                   AND g.currency = ?
                 ORDER BY g.target_date IS NULL, g.target_date, g.name
                """,
                (rs, row) -> new SavingsGoalProgress(
                        rs.getLong("id"),
                        rs.getString("name"),
                        Money.of(rs.getLong("target_minor"), currency),
                        Money.of(rs.getLong("saved_minor"), currency),
                        LocalDate.parse(rs.getString("started_on")),
                        optionalDate(rs, "target_date")),
                currency);
    }

    private static RowMapper<AccountBalance> accountBalanceMapper() {
        return (rs, row) -> new AccountBalance(
                rs.getLong("id"),
                rs.getString("name"),
                AccountType.valueOf(rs.getString("type")),
                Money.of(rs.getLong("balance_minor"), rs.getString("currency")));
    }

    private static LocalDate optionalDate(ResultSet rs, String column) throws SQLException {
        String value = rs.getString(column);
        return value == null ? null : LocalDate.parse(value);
    }
}
