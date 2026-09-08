package dev.igherga.moneymind.common;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * An exact monetary amount, held as a signed count of minor units plus an ISO
 * 4217 currency code.
 *
 * <p>Integer minor units rather than {@code double} or {@code BigDecimal}: the
 * former cannot represent 0.10 exactly, and the latter would land in SQLite as
 * {@code TEXT} or — worse, silently — {@code REAL}. Addition and subtraction of
 * minor units are exact and closed, so {@code SUM()} in SQL is exact too.
 *
 * <p>The minor-unit exponent is <em>per currency</em>: EUR and USD use 2, JPY
 * uses 0, TND uses 3. It is always derived from
 * {@link Currency#getDefaultFractionDigits()} and never hardcoded as 100.
 *
 * <p>Amounts are signed from the owning account's perspective: negative means
 * money left the account.
 */
public record Money(long minorUnits, String currency) implements Comparable<Money> {

    public Money {
        Objects.requireNonNull(currency, "currency must not be null");
        currency = currency.trim().toUpperCase(Locale.ROOT);
        // Rejects anything that is not a known ISO 4217 code.
        Currency.getInstance(currency);
    }

    public static Money of(long minorUnits, String currency) {
        return new Money(minorUnits, currency);
    }

    public static Money zero(String currency) {
        return new Money(0L, currency);
    }

    /**
     * Builds an amount from a major-unit decimal, for example {@code 12.34} EUR.
     * Throws {@link ArithmeticException} if the value carries more precision
     * than the currency supports, rather than quietly rounding it away.
     */
    public static Money fromMajor(BigDecimal major, String currency) {
        Objects.requireNonNull(major, "major must not be null");
        int digits = Money.zero(currency).fractionDigits();
        return new Money(major.setScale(digits).movePointRight(digits).longValueExact(), currency);
    }

    /** Minor-unit exponent for this currency: EUR 2, JPY 0, TND 3. */
    public int fractionDigits() {
        int digits = Currency.getInstance(currency).getDefaultFractionDigits();
        // Pseudo-currencies such as XXX report -1; treat them as whole units.
        return Math.max(digits, 0);
    }

    /** The amount in major units, for display and serialisation only. */
    public BigDecimal toMajor() {
        return BigDecimal.valueOf(minorUnits, fractionDigits());
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(minorUnits, other.minorUnits), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(minorUnits, other.minorUnits), currency);
    }

    public Money times(long factor) {
        return new Money(Math.multiplyExact(minorUnits, factor), currency);
    }

    public Money negate() {
        return new Money(Math.negateExact(minorUnits), currency);
    }

    public Money abs() {
        return minorUnits < 0 ? negate() : this;
    }

    public boolean isZero() {
        return minorUnits == 0;
    }

    public boolean isPositive() {
        return minorUnits > 0;
    }

    public boolean isNegative() {
        return minorUnits < 0;
    }

    /**
     * Sums amounts of one currency, starting from zero so an empty input is
     * still well defined. Mixing currencies throws.
     */
    public static Money sum(String currency, Iterable<Money> amounts) {
        Money total = Money.zero(currency);
        for (Money amount : amounts) {
            total = total.plus(amount);
        }
        return total;
    }

    /**
     * Splits this amount across the given weights so that the parts sum back
     * <em>exactly</em> to the original, using the largest-remainder method:
     * every part gets its floor share, then the leftover minor units go one
     * each to the largest remainders (ties resolved by position).
     *
     * <p>This is the only place in the codebase where money is divided. Naive
     * division loses or invents minor units — splitting 10.00 three ways as
     * 3.33 each leaves a cent unaccounted for.
     */
    public List<Money> allocate(int... weights) {
        if (weights == null || weights.length == 0) {
            throw new IllegalArgumentException("at least one weight is required");
        }
        long totalWeight = 0;
        for (int weight : weights) {
            if (weight < 0) {
                throw new IllegalArgumentException("weights must not be negative: " + weight);
            }
            totalWeight = Math.addExact(totalWeight, weight);
        }
        if (totalWeight == 0) {
            throw new IllegalArgumentException("weights must not sum to zero");
        }

        long[] shares = new long[weights.length];
        long[] remainders = new long[weights.length];
        long distributed = 0;
        for (int i = 0; i < weights.length; i++) {
            long numerator = Math.multiplyExact(minorUnits, (long) weights[i]);
            // floorDiv/floorMod keep the remainder non-negative for negative
            // amounts too, so the leftover loop below works in both directions.
            shares[i] = Math.floorDiv(numerator, totalWeight);
            remainders[i] = Math.floorMod(numerator, totalWeight);
            distributed += shares[i];
        }

        long leftover = minorUnits - distributed;
        Integer[] order = new Integer[weights.length];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        // Stable sort, so equal remainders keep ascending index order.
        Arrays.sort(order, Comparator.<Integer>comparingLong(i -> remainders[i]).reversed());
        for (int k = 0; k < leftover; k++) {
            shares[order[k]]++;
        }

        List<Money> parts = new ArrayList<>(weights.length);
        for (long share : shares) {
            parts.add(new Money(share, currency));
        }
        return List.copyOf(parts);
    }

    /** Convenience for an even split; see {@link #allocate(int...)}. */
    public List<Money> allocateEvenly(int parts) {
        if (parts < 1) {
            throw new IllegalArgumentException("parts must be at least 1, was " + parts);
        }
        int[] weights = new int[parts];
        Arrays.fill(weights, 1);
        return allocate(weights);
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return Long.compare(minorUnits, other.minorUnits);
    }

    @Override
    public String toString() {
        return toMajor().toPlainString() + " " + currency;
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other must not be null");
        if (!currency.equals(other.currency)) {
            throw new CurrencyMismatchException(currency, other.currency);
        }
    }
}
