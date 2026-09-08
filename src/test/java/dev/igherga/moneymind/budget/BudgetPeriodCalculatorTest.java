package dev.igherga.moneymind.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;

/**
 * A plain unit test, because the calculator is a plain class. The cases here
 * are the ones that produce an off-by-a-month bug in production and never in a
 * demo: short months and anchors that fall before today.
 */
class BudgetPeriodCalculatorTest {

    @Test
    @DisplayName("a calendar-month budget spans the whole month")
    void calendarMonth() {
        BudgetPeriodCalculator.Period period =
                BudgetPeriodCalculator.monthly(LocalDate.of(2026, 9, 7), 1);

        assertThat(period.startsOn()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(period.endsOn()).isEqualTo(LocalDate.of(2026, 9, 30));
    }

    @Test
    @DisplayName("before the anchor day, the live period is the previous one")
    void beforeTheAnchorTheperiodIsPrevious() {
        // Anchored on the 25th, the 3rd of June belongs to the period from 25 May.
        BudgetPeriodCalculator.Period period =
                BudgetPeriodCalculator.monthly(LocalDate.of(2026, 6, 3), 25);

        assertThat(period.startsOn()).isEqualTo(LocalDate.of(2026, 5, 25));
        assertThat(period.endsOn()).isEqualTo(LocalDate.of(2026, 6, 24));
    }

    @Test
    @DisplayName("on the anchor day, the new period has already begun")
    void onTheAnchorTheNewPeriodStarts() {
        BudgetPeriodCalculator.Period period =
                BudgetPeriodCalculator.monthly(LocalDate.of(2026, 6, 25), 25);

        assertThat(period.startsOn()).isEqualTo(LocalDate.of(2026, 6, 25));
    }

    @Test
    @DisplayName("an anchor of 31 clamps to the last day of a short month")
    void anchorClampsInShortMonths() {
        // February has no 31st. Rolling into March would leave a gap.
        BudgetPeriodCalculator.Period period =
                BudgetPeriodCalculator.monthly(LocalDate.of(2026, 2, 15), 31);

        assertThat(period.startsOn()).isEqualTo(LocalDate.of(2026, 1, 31));
        assertThat(period.endsOn()).isEqualTo(LocalDate.of(2026, 2, 27));
    }

    @Test
    @DisplayName("consecutive periods are contiguous, leaving no uncovered day")
    void periodsLeaveNoGap() {
        LocalDate cursor = LocalDate.of(2026, 1, 1);
        BudgetPeriodCalculator.Period previous = null;

        // A whole year at an awkward anchor: every day must belong to exactly
        // one period, which is what a gap or an overlap would break.
        while (cursor.isBefore(LocalDate.of(2027, 1, 1))) {
            BudgetPeriodCalculator.Period period = BudgetPeriodCalculator.monthly(cursor, 31);
            assertThat(period.contains(cursor)).isTrue();
            if (previous != null && !period.equals(previous)) {
                assertThat(period.startsOn()).isEqualTo(previous.endsOn().plusDays(1));
            }
            previous = period;
            cursor = cursor.plusDays(1);
        }
    }

    @Test
    @DisplayName("a weekly period runs seven days from its start day")
    void weekly() {
        BudgetPeriodCalculator.Period period = BudgetPeriodCalculator.weekly(
                LocalDate.of(2026, 9, 10), DayOfWeek.MONDAY);

        assertThat(period.startsOn()).isEqualTo(LocalDate.of(2026, 9, 7));
        assertThat(period.endsOn()).isEqualTo(LocalDate.of(2026, 9, 13));
    }

    @Test
    @DisplayName("an impossible anchor day is rejected rather than silently clamped")
    void rejectsBadAnchor() {
        assertThatThrownBy(() -> BudgetPeriodCalculator.monthly(LocalDate.of(2026, 9, 7), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BudgetPeriodCalculator.monthly(LocalDate.of(2026, 9, 7), 32))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
