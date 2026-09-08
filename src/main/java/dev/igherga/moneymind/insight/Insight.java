package dev.igherga.moneymind.insight;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One finding about the user's money, computed deterministically.
 *
 * <p>{@code facts} is the authoritative part and {@code message} is only a
 * rendering of it. Every number the UI displays is read from {@code facts};
 * nothing is ever parsed back out of the message. That is what lets the
 * optional LLM layer reword {@code message} freely — and lets the app run
 * completely with the LLM disabled, which is the default.
 *
 * @param code    stable identifier of the rule that produced this, for tests
 *                and for the UI to key on — never a display string
 * @param tone    how it should read; presentation only
 * @param message the deterministic sentence built from the facts
 * @param facts   the computed values behind the sentence, insertion-ordered
 */
public record Insight(String code, InsightTone tone, String message, Map<String, String> facts) {

    public Insight {
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(tone, "tone must not be null");
        Objects.requireNonNull(message, "message must not be null");
        // Defensive copy that keeps insertion order, so the fact list reads in
        // the order the rule chose. Map.copyOf would be wrong here: it makes no
        // iteration-order guarantee.
        facts = Collections.unmodifiableMap(
                new LinkedHashMap<>(Objects.requireNonNullElseGet(facts, Map::of)));
    }

    /** Returns a copy whose message has been reworded, facts untouched. */
    public Insight withMessage(String reworded) {
        return new Insight(code, tone, reworded, facts);
    }

    /** Builder for the fact map, purely to keep the rules readable. */
    public static Map<String, String> facts(String... keysAndValues) {
        if (keysAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("facts come in key/value pairs");
        }
        Map<String, String> facts = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            facts.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return facts;
    }
}
