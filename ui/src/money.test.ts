import {
  formatAmountForInput,
  formatCompact,
  formatMagnitude,
  formatSigned,
  fractionDigits,
  parseAmountToMinor,
  toMajor,
} from './money'

/**
 * These lock in the one rule that matters at the display edge: the divisor
 * comes from the currency, not from an assumed 100. Getting it wrong is silent
 * and off by two orders of magnitude.
 */
describe('money', () => {
  it('takes the minor-unit exponent from the currency', () => {
    expect(fractionDigits('EUR')).toBe(2)
    expect(fractionDigits('JPY')).toBe(0)
    expect(fractionDigits('TND')).toBe(3)
  })

  it('converts minor units using that exponent', () => {
    expect(toMajor(127760, 'EUR')).toBeCloseTo(1277.6)
    // 127760 yen is 127,760 yen — dividing by 100 here would be a 100x error.
    expect(toMajor(127760, 'JPY')).toBe(127760)
    expect(toMajor(127760, 'TND')).toBeCloseTo(127.76)
  })

  it('keeps the sign where the sign is the information', () => {
    // 25000 minor units of EUR is 250.00, and a negative net worth stays negative.
    expect(formatSigned(-25000, 'EUR')).toBe('-€250.00')
    expect(formatSigned(25000, 'EUR')).toBe('€250.00')
  })

  it('drops the sign for a magnitude shown beside a direction word', () => {
    expect(formatMagnitude(-127760, 'EUR')).not.toContain('-')
  })

  it('keeps a decimal in compact form above a thousand', () => {
    // Rounding 1,277.60 to "€1K" would erase the gap between neighbouring bars.
    expect(formatCompact(127760, 'EUR')).toMatch(/1\.3/)
  })
})

describe('parsing typed amounts', () => {
  it('builds minor units from digits rather than by multiplying', () => {
    // parseFloat('12.34') * 100 is 1234.0000000000002; this must be exact.
    expect(parseAmountToMinor('12.34', 'EUR')).toBe(1234)
    expect(parseAmountToMinor('0.07', 'EUR')).toBe(7)
    expect(parseAmountToMinor('1000', 'EUR')).toBe(100000)
    expect(parseAmountToMinor('.5', 'EUR')).toBe(50)
  })

  it('uses the currency exponent, not a fixed 100', () => {
    expect(parseAmountToMinor('2500', 'JPY')).toBe(2500)
    expect(parseAmountToMinor('12.345', 'TND')).toBe(12345)
  })

  it('accepts a comma as the decimal separator', () => {
    expect(parseAmountToMinor('12,34', 'EUR')).toBe(1234)
  })

  it('refuses more precision than the currency has', () => {
    expect(() => parseAmountToMinor('10.123', 'EUR')).toThrow(/at most 2 decimal places/)
    expect(() => parseAmountToMinor('10.5', 'JPY')).toThrow(/no decimal places/)
  })

  it('refuses text that is not an amount', () => {
    for (const bad of ['', '  ', 'abc', '1.2.3', '-']) {
      expect(() => parseAmountToMinor(bad, 'EUR')).toThrow()
    }
  })

  it('round-trips through the editable form', () => {
    for (const minor of [0, 7, 1234, -4210, 320000]) {
      expect(parseAmountToMinor(formatAmountForInput(minor, 'EUR'), 'EUR')).toBe(minor)
    }
    expect(formatAmountForInput(2500, 'JPY')).toBe('2500')
  })
})
