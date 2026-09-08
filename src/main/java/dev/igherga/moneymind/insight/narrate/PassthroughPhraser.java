package dev.igherga.moneymind.insight.narrate;

import dev.igherga.moneymind.insight.Insight;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The default phraser: returns the rule's own deterministic sentence.
 *
 * <p>This is not a placeholder for the LLM — it is the baseline the product is
 * specified against. The templates in the rules are meant to be good enough to
 * ship, so that turning the model off costs polish and never meaning.
 *
 * <p>Registered whenever {@code moneymind.ai.enabled} is not {@code true},
 * which includes the property being absent.
 */
@Component
@ConditionalOnProperty(name = "moneymind.ai.enabled", havingValue = "false", matchIfMissing = true)
class PassthroughPhraser implements InsightPhraser {

    @Override
    public Insight phrase(Insight insight) {
        return insight;
    }
}
