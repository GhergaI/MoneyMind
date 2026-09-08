/**
 * Turning minor units into text.
 *
 * The API sends every amount as an integer count of minor units plus one
 * currency code, because a JSON number is an IEEE double in the browser and
 * `0.1` does not survive that. Division happens here, once, at the edge of the
 * display layer — and the divisor comes from the currency, never a hardcoded
 * 100. EUR has two fraction digits, JPY none, TND three.
 */

/** Formatters are not free to construct; the same few are reused constantly. */
const currencyFormatters = new Map<string, Intl.NumberFormat>()
const digitsByCurrency = new Map<string, number>()

function currencyFormatter(currency: string): Intl.NumberFormat {
  let formatter = currencyFormatters.get(currency)
  if (!formatter) {
    formatter = new Intl.NumberFormat(undefined, { style: 'currency', currency })
    currencyFormatters.set(currency, formatter)
  }
  return formatter
}

/**
 * The currency's minor-unit exponent, taken from Intl rather than a table of
 * our own. Falls back to 2 only if Intl reports nothing usable.
 */
export function fractionDigits(currency: string): number {
  const cached = digitsByCurrency.get(currency)
  if (cached !== undefined) return cached
  const resolved = currencyFormatter(currency).resolvedOptions().maximumFractionDigits
  const digits = typeof resolved === 'number' && Number.isInteger(resolved) ? resolved : 2
  digitsByCurrency.set(currency, digits)
  return digits
}

/** Minor units to a major-unit number, for formatting and chart geometry only. */
export function toMajor(minorUnits: number, currency: string): number {
  return minorUnits / 10 ** fractionDigits(currency)
}

/**
 * Formats an amount exactly as given, sign included. Use this where the sign is
 * the information — a net worth that can genuinely be negative.
 */
export function formatSigned(minorUnits: number, currency: string): string {
  return currencyFormatter(currency).format(toMajor(minorUnits, currency))
}

/**
 * Formats the magnitude, dropping the sign.
 *
 * Amounts are stored signed from the account's perspective, so spending arrives
 * negative. A "spent" tile reads better as `€1,277.60` beside the word "spent"
 * than as `-€1,277.60`, and this is the presentation-layer flip the invariants
 * reserve for exactly this moment.
 */
export function formatMagnitude(minorUnits: number, currency: string): string {
  return currencyFormatter(currency).format(Math.abs(toMajor(minorUnits, currency)))
}

/**
 * Short form for axis ticks and dense labels: `€1.3K`, `€82`. Compact notation
 * would otherwise round 1,277.60 to "€1K" and lose the difference between two
 * neighbouring bars, so this keeps one decimal above a thousand.
 */
export function formatCompact(minorUnits: number, currency: string): string {
  const major = Math.abs(toMajor(minorUnits, currency))
  return new Intl.NumberFormat(undefined, {
    style: 'currency',
    currency,
    notation: 'compact',
    maximumFractionDigits: major >= 1000 ? 1 : 0,
  }).format(major)
}

/** A whole-number percentage for meters and shares. */
export function formatPercent(fraction: number): string {
  return `${Math.round(fraction * 100)}%`
}

/** `2026-09-07` to a short readable date. Dates arrive as ISO strings. */
export function formatDay(isoDate: string): string {
  const [year, month, day] = isoDate.split('-').map(Number)
  // Constructed from parts rather than `new Date(iso)`: parsing a bare date
  // string is UTC, which shifts the day backwards for anyone west of London.
  return new Date(year, month - 1, day).toLocaleDateString(undefined, {
    day: 'numeric',
    month: 'short',
  })
}

/** Thrown when typed text is not a valid amount for the currency. */
export class AmountFormatError extends Error {}

/**
 * Parses typed text into minor units.
 *
 * **Built by concatenating digits, never by multiplying.** `parseFloat('12.34')
 * * 100` is `1234.0000000000002`, and rounding that away works until it
 * doesn't. Assembling the integer from the digit strings is exact by
 * construction, which is the same reasoning that keeps money out of `double` on
 * the server.
 *
 * Accepts a comma or a dot as the decimal separator, since which one a keyboard
 * produces is a locale accident. Rejects more precision than the currency has —
 * silently rounding away a third decimal is how a cent goes missing.
 */
export function parseAmountToMinor(text: string, currency: string): number {
  const cleaned = text.trim().replace(/[\s ]/g, '').replace(',', '.')
  if (!/^-?\d*(\.\d*)?$/.test(cleaned) || !/\d/.test(cleaned)) {
    throw new AmountFormatError('Enter an amount, for example 12.34')
  }

  const negative = cleaned.startsWith('-')
  const [whole = '', fraction = ''] = cleaned.replace('-', '').split('.')
  const digits = fractionDigits(currency)

  if (fraction.length > digits) {
    throw new AmountFormatError(
      digits === 0
        ? `${currency} amounts have no decimal places`
        : `${currency} amounts have at most ${digits} decimal places`,
    )
  }

  const minor = Number(`${whole || '0'}${fraction.padEnd(digits, '0')}`)
  if (!Number.isSafeInteger(minor)) {
    throw new AmountFormatError('That amount is too large')
  }
  return negative ? -minor : minor
}

/**
 * Minor units as a plain editable decimal — no currency symbol or grouping, so
 * it can go straight back into a text input and be re-parsed unchanged.
 */
export function formatAmountForInput(minorUnits: number, currency: string): string {
  const digits = fractionDigits(currency)
  const magnitude = Math.abs(minorUnits).toString().padStart(digits + 1, '0')
  const whole = magnitude.slice(0, magnitude.length - digits) || '0'
  const fraction = digits === 0 ? '' : `.${magnitude.slice(magnitude.length - digits)}`
  return `${minorUnits < 0 ? '-' : ''}${whole}${fraction}`
}

/** `2026-09` — the month key the API uses for budgets and transaction pages. */
export function monthKey(date: Date): string {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`
}

/** Today as `2026-09-07`, in the viewer's own timezone rather than UTC. */
export function todayIso(): string {
  const now = new Date()
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(
    now.getDate(),
  ).padStart(2, '0')}`
}
