package dev.igherga.moneymind.common;

/**
 * Thrown when an operation would combine amounts in different currencies.
 * Summing across currencies silently produces meaningless numbers, so it is
 * always an error rather than something to coerce.
 */
public class CurrencyMismatchException extends RuntimeException {

    public CurrencyMismatchException(String expected, String actual) {
        super("Cannot combine " + actual + " with " + expected
                + " — amounts in different currencies must be converted explicitly");
    }
}
