package dev.igherga.moneymind.insight;

import dev.igherga.moneymind.insight.narrate.InsightPhraser;
import dev.igherga.moneymind.insight.rules.SavingsGoalPaceRule;
import dev.igherga.moneymind.insight.rules.SpendingVersusLastMonthRule;
import dev.igherga.moneymind.insight.rules.TopSpendingCategoryRule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs every rule over one {@link AnalysisContext} and phrases the results.
 *
 * <p>The rule list is assembled here by hand rather than by component scanning.
 * That is deliberate: the rules stay free of Spring annotations so they can be
 * unit-tested by constructing a context literal, and the order they appear in
 * on the dashboard becomes an explicit, reviewable decision instead of a
 * classpath accident.
 *
 * <p>A rule that throws is dropped rather than allowed to take the dashboard
 * down with it. An insight is a nicety; the balances above it are not.
 */
@Service
public class InsightService {

    private static final Logger log = LoggerFactory.getLogger(InsightService.class);

    /** Display order on the dashboard. */
    private final List<InsightRule> rules = List.of(
            new SpendingVersusLastMonthRule(),
            new TopSpendingCategoryRule(),
            new SavingsGoalPaceRule());

    private final ObjectProvider<InsightPhraser> phraser;

    InsightService(ObjectProvider<InsightPhraser> phraser) {
        this.phraser = phraser;
    }

    public List<Insight> analyse(AnalysisContext context) {
        List<Insight> insights = new ArrayList<>(rules.size());
        for (InsightRule rule : rules) {
            try {
                rule.evaluate(context).map(this::phrase).ifPresent(insights::add);
            } catch (RuntimeException failure) {
                log.warn("Insight rule {} failed and was skipped", rule.code(), failure);
            }
        }
        return List.copyOf(insights);
    }

    /**
     * Applies the configured phraser, falling back to the rule's own sentence.
     *
     * <p>Resolved through an {@link ObjectProvider} so that enabling the LLM
     * before its phraser exists degrades to the template rather than refusing
     * to start, and so a phraser that fails mid-request cannot blank an
     * insight whose facts are already computed and correct.
     */
    private Insight phrase(Insight insight) {
        InsightPhraser configured = phraser.getIfAvailable();
        if (configured == null) {
            return insight;
        }
        try {
            Insight phrased = configured.phrase(insight);
            return phrased == null ? insight : phrased;
        } catch (RuntimeException failure) {
            log.warn("Phrasing insight {} failed; using the deterministic template",
                    insight.code(), failure);
            return insight;
        }
    }
}
