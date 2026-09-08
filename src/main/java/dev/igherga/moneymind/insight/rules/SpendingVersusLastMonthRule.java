package dev.igherga.moneymind.insight.rules;

import dev.igherga.moneymind.common.Money;
import dev.igherga.moneymind.insight.AnalysisContext;
import dev.igherga.moneymind.insight.Insight;
import dev.igherga.moneymind.insight.InsightRule;
import dev.igherga.moneymind.insight.InsightTone;
import dev.igherga.moneymind.insight.MoneyText;

import java.util.Optional;

/**
 * Compares this month's spending with the previous month's.
 *
 * <p><b>Like for like.</b> The context supplies the previous month's total over
 * the <em>same number of elapsed days</em>, not its full total. Comparing four
 * days against thirty-one would congratulate the user every month until the
 * final week, which is exactly the kind of flattering nonsense that makes a
 * finance app untrustworthy.
 *
 * <p>Both figures are signed expense sums and therefore negative, so the
 * comparison is on magnitudes. A change smaller than {@link #NOISE_FLOOR} is
 * reported as "about the same" — declaring a 0.4% drop a win is noise dressed
 * as insight.
 */
public final class SpendingVersusLastMonthRule implements InsightRule {

    public static final String CODE = "SPENDING_VS_LAST_MONTH";

    /** Relative change below which the two months are called equivalent. */
    private static final double NOISE_FLOOR = 0.05d;

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public Optional<Insight> evaluate(AnalysisContext context) {
        Money current = context.spendThisMonth().abs();
        Money previous = context.spendPreviousPeriod().abs();

        // With no baseline there is nothing to compare against; saying "you
        // spent more than last month" on a first-ever month would be a lie.
        if (previous.isZero()) {
            return Optional.empty();
        }

        long difference = current.minorUnits() - previous.minorUnits();
        double change = (double) difference / (double) previous.minorUnits();

        InsightTone tone;
        String message;
        if (Math.abs(change) < NOISE_FLOOR) {
            tone = InsightTone.NEUTRAL;
            message = "You're spending about the same as last month.";
        } else if (difference < 0) {
            tone = InsightTone.POSITIVE;
            message = "You're spending less than last month — "
                    + MoneyText.amount(Money.of(difference, current.currency()))
                    + " less so far.";
        } else {
            tone = InsightTone.ATTENTION;
            message = "You're spending more than last month — "
                    + MoneyText.amount(Money.of(difference, current.currency()))
                    + " more so far.";
        }

        return Optional.of(new Insight(CODE, tone, message, Insight.facts(
                "currency", current.currency(),
                "spentThisMonthMinor", Long.toString(current.minorUnits()),
                "spentPreviousPeriodMinor", Long.toString(previous.minorUnits()),
                "differenceMinor", Long.toString(difference),
                "changePercent", Long.toString(Math.round(change * 100d)),
                "comparedDays", Integer.toString(context.elapsedDays()))));
    }
}
