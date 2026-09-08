package dev.igherga.moneymind.config;

import dev.igherga.moneymind.common.Money;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Random;

/**
 * Fills an empty ledger with a few months of plausible activity, so the
 * dashboard can be looked at and judged before manual entry exists.
 *
 * <p><b>This is a development aid and is meant to be deleted.</b> It is not a
 * fixture the tests depend on and not a product feature. Turn it off with
 * {@code moneymind.demo-data.enabled=false}; remove the class once Phase 1
 * gives the app real entry.
 *
 * <p>Two safety properties make it hard to regret. It runs only when the
 * {@code txn} table is completely empty, so it can never touch, duplicate or
 * reinterpret data that was entered for real. And every amount is written
 * through {@link Money#fromMajor}, so the minor-unit exponent comes from the
 * currency rather than an assumed 100 — seeding a JPY or TND ledger produces
 * correct integers rather than amounts off by two orders of magnitude.
 *
 * <p>The data deliberately includes the two cases that break naive reporting: a
 * monthly transfer into savings, which must not read as spending, and a refund,
 * which must reduce a category's spend rather than count as income.
 */
@Component
@ConditionalOnProperty(name = "moneymind.demo-data.enabled", havingValue = "true")
// Must run after FirstRunBootstrap, which creates the 'Main' account this reads.
// An unannotated ApplicationRunner sits at LOWEST_PRECEDENCE, so FirstRunBootstrap
// carries an explicit @Order too — without it, this would run first.
@Order(DemoDataSeeder.ORDER)
class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    /** Runs after {@link FirstRunBootstrap#ORDER}. */
    static final int ORDER = FirstRunBootstrap.ORDER + 100;

    /** Months of history to generate, the current partial month included. */
    private static final int MONTHS = 6;

    private final JdbcTemplate jdbc;

    /** Fixed seed: restarting must not reshuffle the numbers being looked at. */
    private final Random variation = new Random(20260907L);

    private String currency;

    DemoDataSeeder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Integer existing = jdbc.queryForObject("SELECT COUNT(*) FROM txn", Integer.class);
        if (existing != null && existing > 0) {
            return;
        }

        long current = accountId("Main");
        currency = jdbc.queryForObject(
                "SELECT currency FROM account WHERE id = ?", String.class, current);

        long savings = createAccount("Savings", "SAVINGS", "0", 20);
        long card = createAccount("Credit card", "CREDIT_CARD", "0", 30);

        YearMonth thisMonth = YearMonth.now();
        LocalDate today = LocalDate.now();
        for (int back = MONTHS - 1; back >= 0; back--) {
            seedMonth(thisMonth.minusMonths(back), today, current, savings, card);
        }

        seedBudgets(thisMonth);
        seedSavingsGoal(savings, today);

        Integer written = jdbc.queryForObject("SELECT COUNT(*) FROM txn", Integer.class);
        log.warn("Demo data enabled: seeded {} transactions across {} months in {}. "
                + "Set moneymind.demo-data.enabled=false to stop this.", written, MONTHS, currency);
    }

    private void seedMonth(YearMonth month, LocalDate today, long current, long savings, long card) {
        // Salary on the 1st, then the month's outgoings spread through it.
        income(current, "SALARY", day(month, 1, today), "Monthly salary", "3200.00");

        expense(current, "HOUSING", day(month, 2, today), "Rent", "1150.00");
        expense(current, "UTILITIES", day(month, 4, today), "Electricity & gas", vary("95.00", 20));
        expense(current, "SUBSCRIPTIONS", day(month, 5, today), "Streaming", "17.99");
        expense(current, "SUBSCRIPTIONS", day(month, 5, today), "Mobile plan", "24.00");

        expense(current, "GROCERIES", day(month, 3, today), "Supermarket", vary("78.40", 25));
        expense(current, "GROCERIES", day(month, 11, today), "Supermarket", vary("64.15", 25));
        expense(current, "GROCERIES", day(month, 19, today), "Supermarket", vary("81.70", 25));
        expense(current, "GROCERIES", day(month, 26, today), "Supermarket", vary("59.90", 25));

        expense(current, "TRANSPORT", day(month, 6, today), "Fuel", vary("62.00", 20));
        expense(current, "TRANSPORT", day(month, 20, today), "Train tickets", vary("28.50", 30));

        // Eating out and entertainment land on the card, so the card carries a
        // balance you owe and the transfer that settles it is not spending.
        expense(card, "EATING_OUT", day(month, 8, today), "Dinner out", vary("46.00", 40));
        expense(card, "EATING_OUT", day(month, 15, today), "Lunch", vary("14.80", 40));
        expense(card, "EATING_OUT", day(month, 22, today), "Coffee & cake", vary("9.60", 40));
        expense(card, "ENTERTAINMENT", day(month, 17, today), "Cinema", vary("24.00", 30));
        expense(card, "SHOPPING", day(month, 13, today), "Clothes", vary("72.00", 60));

        expense(current, "HEALTH", day(month, 21, today), "Pharmacy", vary("18.20", 50));

        // Settling the card and moving money to savings are transfers: they move
        // balances and must stay out of income and spending entirely. Paying the
        // card takes money out of the current account and moves the card's
        // negative balance back toward zero, so the direction is current -> card.
        transfer(current, card, day(month, 28, today), "Credit card payment", "150.00");
        transfer(current, savings, day(month, 28, today), "Monthly saving", "400.00");

        // One returned purchase, as a positive amount in an expense category —
        // it reduces Shopping rather than counting as income.
        if (month.getMonthValue() % 3 == 0) {
            expense(card, "SHOPPING", day(month, 24, today), "Returned item", "-38.00");
        }
    }

    private void seedBudgets(YearMonth month) {
        LocalDate startsOn = month.atDay(1);
        LocalDate endsOn = month.atEndOfMonth();
        budget("GROCERIES", "320.00", startsOn, endsOn);
        budget("EATING_OUT", "90.00", startsOn, endsOn);
        budget("TRANSPORT", "110.00", startsOn, endsOn);
        budget("SHOPPING", "80.00", startsOn, endsOn);
    }

    private void budget(String systemKey, String amount, LocalDate startsOn, LocalDate endsOn) {
        long categoryId = categoryId(systemKey);
        jdbc.update("""
                INSERT INTO budget (category_id, default_amount_minor, period_type, anchor_day,
                                    rollover, currency, active_from)
                VALUES (?, ?, 'MONTHLY', 1, 0, ?, ?)
                """, categoryId, minor(amount), currency, startsOn.toString());
        long budgetId = jdbc.queryForObject("SELECT MAX(id) FROM budget", Long.class);
        jdbc.update("""
                INSERT INTO budget_period (budget_id, starts_on, ends_on, allocated_minor, rollover_in_minor)
                VALUES (?, ?, ?, ?, 0)
                """, budgetId, startsOn.toString(), endsOn.toString(), minor(amount));
    }

    /**
     * One account-backed goal. {@code created_at} is set explicitly rather than
     * defaulted, because the pace rule measures elapsed time from it — a goal
     * created "now" with a future deadline has zero elapsed time and would
     * always report as on track.
     */
    private void seedSavingsGoal(long savingsAccount, LocalDate today) {
        jdbc.update("""
                INSERT INTO savings_goal (name, target_minor, currency, target_date, account_id,
                                          funding_mode, created_at)
                VALUES (?, ?, ?, ?, ?, 'ACCOUNT_BALANCE', ?)
                """,
                "Emergency fund", minor("6000.00"), currency,
                today.plusMonths(8).toString(), savingsAccount,
                today.minusMonths(5).atStartOfDay().toString());
    }

    // --- writing rows -------------------------------------------------------

    private void income(long account, String systemKey, LocalDate on, String description, String amount) {
        if (on == null) {
            return;
        }
        insert(account, categoryId(systemKey), null, "INCOME", minor(amount), on, description);
    }

    /** A negative amount: money leaving the account. */
    private void expense(long account, String systemKey, LocalDate on, String description, String amount) {
        if (on == null) {
            return;
        }
        insert(account, categoryId(systemKey), null, "EXPENSE", -minor(amount), on, description);
    }

    /** Two equal and opposite legs sharing one transfer row, never categorised. */
    private void transfer(long from, long to, LocalDate on, String note, String amount) {
        if (on == null) {
            return;
        }
        jdbc.update("INSERT INTO transfer (note) VALUES (?)", note);
        long transferId = jdbc.queryForObject("SELECT MAX(id) FROM transfer", Long.class);
        long minor = minor(amount);
        insert(from, null, transferId, "TRANSFER", -minor, on, note);
        insert(to, null, transferId, "TRANSFER", minor, on, note);
    }

    private void insert(long account, Long category, Long transferId, String kind,
            long amountMinor, LocalDate bookedOn, String description) {
        jdbc.update("""
                INSERT INTO txn (account_id, category_id, transfer_id, kind, amount_minor,
                                 currency, booked_on, description, source)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'MANUAL')
                """,
                account, category, transferId, kind, amountMinor, currency,
                bookedOn.toString(), description);
    }

    private long createAccount(String name, String type, String openingBalance, int sortOrder) {
        jdbc.update("""
                INSERT INTO account (name, type, currency, opening_balance_minor, sort_order)
                VALUES (?, ?, ?, ?, ?)
                """, name, type, currency, minor(openingBalance), sortOrder);
        return jdbc.queryForObject("SELECT MAX(id) FROM account", Long.class);
    }

    // --- helpers ------------------------------------------------------------

    /**
     * The given day of {@code month}, or {@code null} when that day has not
     * happened yet — a seeded ledger must not contain future transactions, which
     * would make the current month look complete and skew every comparison.
     * Days beyond the month's length are clamped to its last day.
     */
    private LocalDate day(YearMonth month, int dayOfMonth, LocalDate today) {
        LocalDate date = month.atDay(Math.min(dayOfMonth, month.lengthOfMonth()));
        return date.isAfter(today) ? null : date;
    }

    /**
     * Converts a major-unit decimal string to minor units using the currency's
     * own exponent. Never divides or multiplies by a hardcoded 100.
     */
    private long minor(String major) {
        int digits = Money.zero(currency).fractionDigits();
        BigDecimal scaled = new BigDecimal(major).setScale(digits, RoundingMode.HALF_UP);
        return Money.fromMajor(scaled, currency).minorUnits();
    }

    /** Nudges an amount by up to {@code percent} either way, deterministically. */
    private String vary(String major, int percent) {
        double factor = 1d + (variation.nextDouble() * 2d - 1d) * (percent / 100d);
        return new BigDecimal(major)
                .multiply(BigDecimal.valueOf(factor))
                .setScale(2, RoundingMode.HALF_UP)
                .toPlainString();
    }

    private long accountId(String name) {
        return jdbc.queryForObject("SELECT id FROM account WHERE name = ?", Long.class, name);
    }

    private long categoryId(String systemKey) {
        return jdbc.queryForObject(
                "SELECT id FROM category WHERE system_key = ?", Long.class, systemKey);
    }
}
