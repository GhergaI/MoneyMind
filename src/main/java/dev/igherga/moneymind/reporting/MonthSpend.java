package dev.igherga.moneymind.reporting;

import dev.igherga.moneymind.common.Money;

import java.time.YearMonth;

/** One point on the spending trend: a calendar month and its signed total. */
public record MonthSpend(YearMonth month, Money spent) {
}
