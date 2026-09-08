package dev.igherga.moneymind.insight.rules;

import dev.igherga.moneymind.insight.AnalysisContext;
import dev.igherga.moneymind.insight.Insight;
import dev.igherga.moneymind.insight.InsightRule;
import dev.igherga.moneymind.insight.InsightTone;
import dev.igherga.moneymind.insight.MoneyText;
import dev.igherga.moneymind.reporting.SavingsGoalProgress;

import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * Judges whether the soonest savings goal is keeping pace.
 *
 * <p>"On track" is a comparison of two fractions: how much of the target is
 * saved, against how much of the goal's timeline has elapsed. Saving 40% of the
 * target is on track at the timeline's halfway point only if the deadline is
 * still far enough away — a bare percentage says nothing about pace.
 *
 * <p>Goals without a target date are skipped: with no deadline there is no pace
 * to be on or off, and inventing one would put a deadline in the user's mouth.
 */
public final class SavingsGoalPaceRule implements InsightRule {

    public static final String CODE = "SAVINGS_GOAL_PACE";

    /**
     * Tolerance on the pace comparison. Being a percentage point behind is not
     * "behind" in any way the user should be told about.
     */
    private static final double TOLERANCE = 0.05d;

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public Optional<Insight> evaluate(AnalysisContext context) {
        Optional<SavingsGoalProgress> soonest = context.goals().stream()
                .filter(goal -> goal.targetDate() != null)
                .filter(goal -> goal.target().isPositive())
                .findFirst();
        if (soonest.isEmpty()) {
            return Optional.empty();
        }

        SavingsGoalProgress goal = soonest.get();
        double reached = goal.reachedFraction();

        // Already there: the pace question is moot and this is worth celebrating.
        if (reached >= 1.0d) {
            return Optional.of(insight(goal, reached, 1.0d, InsightTone.POSITIVE,
                    "You've reached your " + goal.name() + " goal."));
        }

        long totalDays = ChronoUnit.DAYS.between(goal.startedOn(), goal.targetDate());
        if (totalDays <= 0) {
            // Target date on or before the goal's own start: no timeline to
            // measure against, so report progress without claiming a pace.
            return Optional.of(insight(goal, reached, 0d, InsightTone.ATTENTION,
                    "Your " + goal.name() + " goal is past its target date, at "
                            + MoneyText.percent(reached) + " saved."));
        }

        long elapsedDays = ChronoUnit.DAYS.between(goal.startedOn(), context.today());
        double expected = Math.min(1.0d, Math.max(0d, (double) elapsedDays / (double) totalDays));

        InsightTone tone;
        String message;
        if (context.today().isAfter(goal.targetDate())) {
            tone = InsightTone.ATTENTION;
            message = "Your " + goal.name() + " goal is past its target date, at "
                    + MoneyText.percent(reached) + " saved.";
        } else if (reached + TOLERANCE >= expected) {
            tone = InsightTone.POSITIVE;
            message = "You're on track to reach your " + goal.name() + " goal — "
                    + MoneyText.amount(goal.saved()) + " of "
                    + MoneyText.amount(goal.target()) + " saved.";
        } else {
            tone = InsightTone.ATTENTION;
            message = "You're behind on your " + goal.name() + " goal — "
                    + MoneyText.percent(reached) + " saved with "
                    + MoneyText.percent(expected) + " of the time gone.";
        }

        return Optional.of(insight(goal, reached, expected, tone, message));
    }

    private static Insight insight(SavingsGoalProgress goal, double reached, double expected,
            InsightTone tone, String message) {
        return new Insight(CODE, tone, message, Insight.facts(
                "currency", goal.target().currency(),
                "goal", goal.name(),
                "savedMinor", Long.toString(goal.saved().minorUnits()),
                "targetMinor", Long.toString(goal.target().minorUnits()),
                "reachedPercent", Long.toString(Math.round(reached * 100d)),
                "expectedPercent", Long.toString(Math.round(expected * 100d)),
                "targetDate", goal.targetDate() == null ? "" : goal.targetDate().toString()));
    }
}
