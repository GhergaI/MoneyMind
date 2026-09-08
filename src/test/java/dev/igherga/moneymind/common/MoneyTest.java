package dev.igherga.moneymind.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Nested
    @DisplayName("minor-unit exponent is per currency, never assumed to be 2")
    class FractionDigits {

        @Test
        void euroHasTwo() {
            assertThat(Money.zero("EUR").fractionDigits()).isEqualTo(2);
        }

        @Test
        void yenHasNone() {
            assertThat(Money.zero("JPY").fractionDigits()).isZero();
        }

        @Test
        void tunisianDinarHasThree() {
            assertThat(Money.zero("TND").fractionDigits()).isEqualTo(3);
        }

        @Test
        void majorConversionFollowsTheExponent() {
            assertThat(Money.of(1234, "EUR").toMajor()).isEqualByComparingTo("12.34");
            assertThat(Money.of(1234, "JPY").toMajor()).isEqualByComparingTo("1234");
            assertThat(Money.of(1234, "TND").toMajor()).isEqualByComparingTo("1.234");
        }

        @Test
        void fromMajorRoundTripsPerCurrency() {
            assertThat(Money.fromMajor(new BigDecimal("12.34"), "EUR").minorUnits()).isEqualTo(1234);
            assertThat(Money.fromMajor(new BigDecimal("1234"), "JPY").minorUnits()).isEqualTo(1234);
            assertThat(Money.fromMajor(new BigDecimal("1.234"), "TND").minorUnits()).isEqualTo(1234);
        }

        @Test
        void fromMajorRefusesToSilentlyDropPrecision() {
            assertThatExceptionOfType(ArithmeticException.class)
                    .isThrownBy(() -> Money.fromMajor(new BigDecimal("12.345"), "EUR"));
            assertThatExceptionOfType(ArithmeticException.class)
                    .isThrownBy(() -> Money.fromMajor(new BigDecimal("0.5"), "JPY"));
        }
    }

    @Nested
    @DisplayName("currencies are never mixed")
    class CurrencyMixing {

        @Test
        void addingDifferentCurrenciesThrows() {
            assertThatExceptionOfType(CurrencyMismatchException.class)
                    .isThrownBy(() -> Money.of(100, "EUR").plus(Money.of(100, "USD")));
        }

        @Test
        void comparingDifferentCurrenciesThrows() {
            assertThatExceptionOfType(CurrencyMismatchException.class)
                    .isThrownBy(() -> Money.of(100, "EUR").compareTo(Money.of(100, "GBP")));
        }

        @Test
        void unknownCurrencyCodeIsRejected() {
            assertThatIllegalArgumentException().isThrownBy(() -> Money.of(1, "ZZZ"));
        }

        @Test
        void currencyCodeIsNormalised() {
            assertThat(Money.of(1, " eur ").currency()).isEqualTo("EUR");
        }
    }

    @Nested
    @DisplayName("arithmetic is exact")
    class Arithmetic {

        @Test
        void addAndSubtract() {
            Money ten = Money.of(1000, "EUR");
            assertThat(ten.plus(Money.of(234, "EUR"))).isEqualTo(Money.of(1234, "EUR"));
            assertThat(ten.minus(Money.of(1500, "EUR"))).isEqualTo(Money.of(-500, "EUR"));
        }

        @Test
        void overflowFailsLoudlyRatherThanWrapping() {
            assertThatExceptionOfType(ArithmeticException.class)
                    .isThrownBy(() -> Money.of(Long.MAX_VALUE, "EUR").plus(Money.of(1, "EUR")));
        }

        @Test
        void sumOfNothingIsZero() {
            assertThat(Money.sum("EUR", List.of())).isEqualTo(Money.zero("EUR"));
        }

        @Test
        void sumAddsUp() {
            List<Money> amounts = List.of(Money.of(100, "EUR"), Money.of(250, "EUR"), Money.of(-50, "EUR"));
            assertThat(Money.sum("EUR", amounts)).isEqualTo(Money.of(300, "EUR"));
        }
    }

    @Nested
    @DisplayName("allocation never loses or invents a minor unit")
    class Allocation {

        @Test
        void tenEurosSplitThreeWaysSumsBackExactly() {
            List<Money> parts = Money.of(1000, "EUR").allocateEvenly(3);
            assertThat(parts).containsExactly(
                    Money.of(334, "EUR"), Money.of(333, "EUR"), Money.of(333, "EUR"));
            assertThat(Money.sum("EUR", parts)).isEqualTo(Money.of(1000, "EUR"));
        }

        @Test
        void weightedSplitSumsBackExactly() {
            List<Money> parts = Money.of(1000, "EUR").allocate(1, 1, 1, 1, 1, 1, 1);
            assertThat(Money.sum("EUR", parts)).isEqualTo(Money.of(1000, "EUR"));
            assertThat(parts).hasSize(7);
        }

        @Test
        void negativeAmountsAlsoSumBackExactly() {
            List<Money> parts = Money.of(-500, "EUR").allocateEvenly(3);
            assertThat(Money.sum("EUR", parts)).isEqualTo(Money.of(-500, "EUR"));
        }

        @Test
        void unevenWeightsFavourTheLargestRemainder() {
            // 100 split 1:1:1 -> 34/33/33, the extra unit going to the first
            // largest remainder rather than being dropped.
            assertThat(Money.of(100, "EUR").allocate(1, 1, 1))
                    .containsExactly(Money.of(34, "EUR"), Money.of(33, "EUR"), Money.of(33, "EUR"));
        }

        @Test
        void proportionalSplitRespectsWeights() {
            List<Money> parts = Money.of(1000, "EUR").allocate(70, 30);
            assertThat(parts).containsExactly(Money.of(700, "EUR"), Money.of(300, "EUR"));
        }

        @Test
        void zeroExponentCurrenciesAllocateInWholeUnits() {
            List<Money> parts = Money.of(100, "JPY").allocateEvenly(3);
            assertThat(Money.sum("JPY", parts)).isEqualTo(Money.of(100, "JPY"));
            assertThat(parts).containsExactly(
                    Money.of(34, "JPY"), Money.of(33, "JPY"), Money.of(33, "JPY"));
        }

        @Test
        void degenerateWeightsAreRejected() {
            assertThatIllegalArgumentException().isThrownBy(() -> Money.of(100, "EUR").allocate());
            assertThatIllegalArgumentException().isThrownBy(() -> Money.of(100, "EUR").allocate(0, 0));
            assertThatIllegalArgumentException().isThrownBy(() -> Money.of(100, "EUR").allocate(1, -1));
            assertThatIllegalArgumentException().isThrownBy(() -> Money.of(100, "EUR").allocateEvenly(0));
        }
    }
}
