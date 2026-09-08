package dev.igherga.moneymind.budget;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

/**
 * Works out which concrete window a budget's period falls in.
 *
 * <p>Deliberately a plain class with no Spring and no JPA, so the awkward cases
 * can be unit-tested directly. They are the whole reason this exists:
 *
 * <ul>
 *   <li><b>An anchor day later than the month is long.</b> A budget anchored on
 *       the 31st has no 31st in February. The anchor is clamped to the month's
 *       last day rather than rolling into March, which would leave a gap no
 *       period covers.</li>
 *   <li><b>The period containing a date is not always the current month's.</b>
 *       With an anchor of the 25th, the 3rd of June belongs to the period that
 *       began on 25 May.</li>
 * </ul>
 *
 * <p>Windows are inclusive of both ends, matching {@code budget_period}'s
 * {@code starts_on}/{@code ends_on} columns and its
 * {@code ends_on >= starts_on} constraint.
 */
public final class BudgetPeriodCalculator {

    private BudgetPeriodCalculator() {
    }

    /** An inclusive date window. */
    public record Period(LocalDate startsOn, LocalDate endsOn) {

        public Period {
            if (endsOn.isBefore(startsOn)) {
                throw new IllegalArgumentException("endsOn " + endsOn + " precedes startsOn " + startsOn);
            }
        }

        public boolean contains(LocalDate date) {
            return !date.isBefore(startsOn) && !date.isAfter(endsOn);
        }
    }

    /**
     * The monthly period containing {@code on}, for a budget anchored on
     * {@code anchorDay}.
     *
     * @param anchorDay 1–31; clamped per month, so 31 means "last day" in a
     *                  short month rather than spilling into the next one
     */
    public static Period monthly(LocalDate on, int anchorDay) {
        requireAnchorDay(anchorDay);

        LocalDate anchorThisMonth = anchorIn(on, anchorDay);
        // Before this month's anchor, the live period is the previous one.
        LocalDate start = on.isBefore(anchorThisMonth)
                ? anchorIn(on.minusMonths(1), anchorDay)
                : anchorThisMonth;
        LocalDate nextStart = anchorIn(start.plusMonths(1), anchorDay);
        return new Period(start, nextStart.minusDays(1));
    }

    /**
     * The weekly period containing {@code on}, starting on {@code startOfWeek}.
     */
    public static Period weekly(LocalDate on, DayOfWeek startOfWeek) {
        LocalDate start = on.with(TemporalAdjusters.previousOrSame(startOfWeek));
        return new Period(start, start.plusDays(6));
    }

    /**
     * The calendar month as a period — the common case, and what the overall
     * monthly budget always uses.
     */
    public static Period calendarMonth(LocalDate on) {
        return new Period(on.withDayOfMonth(1), on.withDayOfMonth(on.lengthOfMonth()));
    }

    /**
     * The anchor day within {@code reference}'s month, clamped to the month's
     * length. Using {@code withDayOfMonth(31)} directly would throw in February.
     */
    private static LocalDate anchorIn(LocalDate reference, int anchorDay) {
        return reference.withDayOfMonth(Math.min(anchorDay, reference.lengthOfMonth()));
    }

    private static void requireAnchorDay(int anchorDay) {
        if (anchorDay < 1 || anchorDay > 31) {
            throw new IllegalArgumentException("anchorDay must be 1..31, was " + anchorDay);
        }
    }
}
