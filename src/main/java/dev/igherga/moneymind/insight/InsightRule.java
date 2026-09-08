package dev.igherga.moneymind.insight;

import java.util.Optional;

/**
 * One deterministic observation about the user's money.
 *
 * <p>Implementations are plain objects: no Spring stereotype, no JPA, no
 * injected repositories. They read {@link AnalysisContext} and return either an
 * {@link Insight} or nothing. Keep them that way — it is what makes each rule
 * unit-testable by constructing a context literal, with no application context
 * to start.
 *
 * <p>Returning {@link Optional#empty()} is the normal case for "not enough data
 * to say anything". A rule must never invent an insight to fill space: a
 * confident sentence about two transactions is worse than silence.
 */
public interface InsightRule {

    /** Stable identifier, matching the {@code code} of the insights produced. */
    String code();

    Optional<Insight> evaluate(AnalysisContext context);
}
