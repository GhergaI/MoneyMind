package dev.igherga.moneymind.insight.rules;

import dev.igherga.moneymind.insight.AnalysisContext;
import dev.igherga.moneymind.insight.Insight;
import dev.igherga.moneymind.insight.InsightRule;
import dev.igherga.moneymind.insight.InsightTone;
import dev.igherga.moneymind.insight.MoneyText;
import dev.igherga.moneymind.reporting.CategorySpend;

import java.util.List;
import java.util.Optional;

/**
 * Names the category the month's spending is concentrated in.
 *
 * <p>The query hands over categories already ordered by spend, so this rule
 * only decides whether saying anything is worthwhile. It stays silent when
 * there is a single category, because "Groceries is your highest spending
 * category" is vacuous when groceries is the <em>only</em> category — the shape
 * of a fact without the substance of one.
 *
 * <p>The tone is deliberately {@link InsightTone#NEUTRAL}: having a largest
 * category is arithmetic, not a mistake. Flagging it as a problem would nag the
 * user about rent every month.
 */
public final class TopSpendingCategoryRule implements InsightRule {

    public static final String CODE = "TOP_SPENDING_CATEGORY";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public Optional<Insight> evaluate(AnalysisContext context) {
        List<CategorySpend> categories = context.spendByCategory();
        if (categories.size() < 2) {
            return Optional.empty();
        }

        CategorySpend top = categories.getFirst();
        long topSpend = top.spent().abs().minorUnits();
        long totalSpend = 0L;
        for (CategorySpend category : categories) {
            totalSpend += category.spent().abs().minorUnits();
        }
        if (totalSpend == 0L) {
            return Optional.empty();
        }

        double share = (double) topSpend / (double) totalSpend;
        String message = top.name() + " is your highest spending category, at "
                + MoneyText.percent(share) + " of this month's spending.";

        return Optional.of(new Insight(CODE, InsightTone.NEUTRAL, message, Insight.facts(
                "currency", top.spent().currency(),
                "category", top.name(),
                "categorySystemKey", top.systemKey() == null ? "" : top.systemKey(),
                "spentMinor", Long.toString(topSpend),
                "totalSpentMinor", Long.toString(totalSpend),
                "sharePercent", Long.toString(Math.round(share * 100d)))));
    }
}
