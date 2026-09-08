package dev.igherga.moneymind.insight;

/**
 * How an insight should feel to read. The product goal is to be motivating
 * rather than overwhelming, so this drives a colour and an icon in the UI and
 * nothing else — it never changes a number.
 */
public enum InsightTone {
    /** Something is going well and is worth reinforcing. */
    POSITIVE,
    /** A neutral observation: a fact about the shape of the month. */
    NEUTRAL,
    /** Worth attention. Deliberately not alarming — this is not a warning siren. */
    ATTENTION
}
