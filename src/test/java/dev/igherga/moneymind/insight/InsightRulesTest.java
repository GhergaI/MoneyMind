package dev.igherga.moneymind.insight;

import static org.assertj.core.api.Assertions.assertThat;

import dev.igherga.moneymind.common.Money;
import dev.igherga.moneymind.insight.rules.SavingsGoalPaceRule;
import dev.igherga.moneymind.insight.rules.SpendingVersusLastMonthRule;
import dev.igherga.moneymind.insight.rules.TopSpendingCategoryRule;
import dev.igherga.moneymind.reporting.CategorySpend;
import dev.igherga.moneymind.reporting.SavingsGoalProgress;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

/**
 * The rules are plain objects, so these are plain unit tests — no Spring
 * context, no database, just a context literal. That is the point of keeping
 * the rules annotation-free.
 */
class InsightRulesTest {

    private static final String EUR = "EUR";
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 10);
    private static final YearMonth MONTH = YearMonth.of(2026, 9);

    private static AnalysisContext context(Money thisMonth, Money previousPeriod,
            List<CategorySpend> categories, List<SavingsGoalProgress> goals) {
        return new AnalysisContext(EUR, MONTH, TODAY, thisMonth, previousPeriod,
                Money.of(320000, EUR), categories, goals);
    }

    private static CategorySpend category(long id, String name, String key, long spentMinor) {
        return new CategorySpend(id, name, key, Money.of(spentMinor, EUR));
    }

    @Nested
    class SpendingVersusLastMonth {

        private final SpendingVersusLastMonthRule rule = new SpendingVersusLastMonthRule();

        @Test
        @DisplayName("says nothing when there is no previous month to compare against")
        void silentWithoutABaseline() {
            Optional<Insight> insight = rule.evaluate(
                    context(Money.of(-50000, EUR), Money.zero(EUR), List.of(), List.of()));

            assertThat(insight).isEmpty();
        }

        @Test
        @DisplayName("reports spending less, with the difference as a fact")
        void spendingLess() {
            Insight insight = rule.evaluate(
                    context(Money.of(-40000, EUR), Money.of(-50000, EUR), List.of(), List.of()))
                    .orElseThrow();

            assertThat(insight.tone()).isEqualTo(InsightTone.POSITIVE);
            assertThat(insight.message()).startsWith("You're spending less than last month");
            assertThat(insight.facts())
                    .containsEntry("differenceMinor", "-10000")
                    .containsEntry("changePercent", "-20")
                    .containsEntry("comparedDays", "10");
        }

        @Test
        @DisplayName("reports spending more")
        void spendingMore() {
            Insight insight = rule.evaluate(
                    context(Money.of(-60000, EUR), Money.of(-50000, EUR), List.of(), List.of()))
                    .orElseThrow();

            assertThat(insight.tone()).isEqualTo(InsightTone.ATTENTION);
            assertThat(insight.message()).startsWith("You're spending more than last month");
        }

        @Test
        @DisplayName("calls a change within the noise floor 'about the same'")
        void smallChangeIsNoise() {
            Insight insight = rule.evaluate(
                    context(Money.of(-51000, EUR), Money.of(-50000, EUR), List.of(), List.of()))
                    .orElseThrow();

            assertThat(insight.tone()).isEqualTo(InsightTone.NEUTRAL);
            assertThat(insight.message()).isEqualTo("You're spending about the same as last month.");
        }
    }

    @Nested
    class TopSpendingCategory {

        private final TopSpendingCategoryRule rule = new TopSpendingCategoryRule();

        @Test
        @DisplayName("names the biggest category and its share")
        void namesTheBiggest() {
            Insight insight = rule.evaluate(context(
                    Money.of(-20000, EUR), Money.of(-20000, EUR),
                    List.of(
                            category(3, "Groceries", "GROCERIES", -15000),
                            category(7, "Eating out", "EATING_OUT", -5000)),
                    List.of())).orElseThrow();

            assertThat(insight.message()).startsWith("Groceries is your highest spending category");
            assertThat(insight.facts())
                    .containsEntry("category", "Groceries")
                    .containsEntry("sharePercent", "75")
                    .containsEntry("spentMinor", "15000");
        }

        @Test
        @DisplayName("says nothing when there is only one category to be highest of")
        void silentWithASingleCategory() {
            Optional<Insight> insight = rule.evaluate(context(
                    Money.of(-15000, EUR), Money.of(-15000, EUR),
                    List.of(category(3, "Groceries", "GROCERIES", -15000)),
                    List.of()));

            assertThat(insight).isEmpty();
        }
    }

    @Nested
    class SavingsGoalPace {

        private final SavingsGoalPaceRule rule = new SavingsGoalPaceRule();

        private SavingsGoalProgress goal(long savedMinor, LocalDate startedOn, LocalDate targetDate) {
            return new SavingsGoalProgress(1L, "Emergency fund",
                    Money.of(600000, EUR), Money.of(savedMinor, EUR), startedOn, targetDate);
        }

        @Test
        @DisplayName("on track when the saved share leads the elapsed share")
        void onTrack() {
            // Half the timeline gone, 60% saved.
            Insight insight = rule.evaluate(context(
                    Money.of(-20000, EUR), Money.of(-20000, EUR), List.of(),
                    List.of(goal(360000, TODAY.minusDays(100), TODAY.plusDays(100)))))
                    .orElseThrow();

            assertThat(insight.tone()).isEqualTo(InsightTone.POSITIVE);
            assertThat(insight.message()).startsWith("You're on track to reach your Emergency fund goal");
            assertThat(insight.facts())
                    .containsEntry("reachedPercent", "60")
                    .containsEntry("expectedPercent", "50");
        }

        @Test
        @DisplayName("behind when the saved share trails the elapsed share")
        void behind() {
            // Three quarters of the timeline gone, only 20% saved.
            Insight insight = rule.evaluate(context(
                    Money.of(-20000, EUR), Money.of(-20000, EUR), List.of(),
                    List.of(goal(120000, TODAY.minusDays(150), TODAY.plusDays(50)))))
                    .orElseThrow();

            assertThat(insight.tone()).isEqualTo(InsightTone.ATTENTION);
            assertThat(insight.message()).startsWith("You're behind on your Emergency fund goal");
        }

        @Test
        @DisplayName("a reached goal is celebrated rather than paced")
        void reached() {
            Insight insight = rule.evaluate(context(
                    Money.of(-20000, EUR), Money.of(-20000, EUR), List.of(),
                    List.of(goal(600000, TODAY.minusDays(100), TODAY.plusDays(100)))))
                    .orElseThrow();

            assertThat(insight.tone()).isEqualTo(InsightTone.POSITIVE);
            assertThat(insight.message()).isEqualTo("You've reached your Emergency fund goal.");
        }

        @Test
        @DisplayName("a goal with no target date has no pace to judge")
        void silentWithoutADeadline() {
            Optional<Insight> insight = rule.evaluate(context(
                    Money.of(-20000, EUR), Money.of(-20000, EUR), List.of(),
                    List.of(goal(120000, TODAY.minusDays(100), null))));

            assertThat(insight).isEmpty();
        }
    }

    @Test
    @DisplayName("an insight's facts keep the order the rule chose")
    void factsKeepInsertionOrder() {
        Insight insight = new Insight("X", InsightTone.NEUTRAL, "message",
                Insight.facts("first", "1", "second", "2", "third", "3"));

        assertThat(insight.facts().keySet()).containsExactly("first", "second", "third");
    }
}
