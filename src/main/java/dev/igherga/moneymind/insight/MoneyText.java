package dev.igherga.moneymind.insight;

import dev.igherga.moneymind.common.Money;

import java.text.NumberFormat;
import java.util.Currency;
import java.util.Locale;

/**
 * Formats amounts for the sentence inside an {@link Insight}.
 *
 * <p>This is presentation only. The UI never parses these strings — it reads the
 * raw minor units out of the insight's facts and formats them itself — so this
 * exists purely so the deterministic fallback sentence reads like English when
 * the LLM layer is off, which is the default.
 *
 * <p>The fraction digits come from the currency via {@link NumberFormat}, so JPY
 * prints no decimals and TND prints three without any special-casing here.
 */
public final class MoneyText {

    private MoneyText() {
    }

    /** Absolute magnitude with a currency symbol, e.g. {@code €1,234.56}. */
    public static String amount(Money money) {
        NumberFormat format = NumberFormat.getCurrencyInstance(Locale.getDefault(Locale.Category.FORMAT));
        format.setCurrency(Currency.getInstance(money.currency()));
        // Currency.getDefaultFractionDigits() is what Money itself uses; asking
        // NumberFormat to honour it keeps the two consistent for odd currencies.
        int digits = money.fractionDigits();
        format.setMinimumFractionDigits(digits);
        format.setMaximumFractionDigits(digits);
        return format.format(money.abs().toMajor());
    }

    /** A whole-number percentage, e.g. {@code 38%}. */
    public static String percent(double fraction) {
        return Math.round(fraction * 100d) + "%";
    }
}
