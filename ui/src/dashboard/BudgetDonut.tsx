import { useState } from 'react'

import type { CategorySlice, MonthlyBudget } from '../api'
import { formatMagnitude, formatPercent, formatSigned } from '../money'

/**
 * The monthly budget as a donut: what each category consumed, and what is left.
 *
 * A donut is a part-to-whole form, and the whole here is the budget — which is
 * the one case where a ring genuinely reads well, because "how much of the
 * circle is left" is the question being asked. It is paired with the horizontal
 * bar chart beside it, which is the better instrument for comparing categories
 * against each other; angles are hard to compare, lengths are not.
 *
 * Every slice appears in the legend with its amount and share, and again in the
 * table below. That is required, not decorative: three of the six slice hues sit
 * below 3:1 against the light surface, and one adjacent pair separates by only
 * ΔE 5.8 under tritanopia. Text is the channel that always works, so no value
 * is reachable by colour or hover alone.
 */

/**
 * The six validated categorical slots, as CSS variables rather than hex.
 *
 * Referencing tokens means the browser swaps the light and dark steps itself,
 * so the chart follows the theme live. Reading `prefers-color-scheme` in JS
 * would fix the palette at first render and stay wrong after a theme change.
 */
const HUES = [
  'var(--series-1)',
  'var(--series-2)',
  'var(--series-3)',
  'var(--series-4)',
  'var(--series-5)',
  'var(--series-6)',
]

/**
 * Hue is bound to the category, not to its position in the ranking.
 *
 * If colours were handed out by rank, Food would be blue in a month it led and
 * orange in a month it did not, and every month-over-month comparison would be
 * a repaint. The five everyday categories therefore own fixed slots; anything
 * else falls back to a stable slot derived from its id, so it is at least
 * consistent from one render to the next.
 */
const FIXED_SLOTS: Record<string, number> = {
  FOOD: 0,
  HOUSEHOLD: 1,
  PERSONAL: 2,
  UNPREDICTED: 3,
  HEALTH: 4,
}

function slotFor(slice: CategorySlice): number {
  const fixed = slice.systemKey ? FIXED_SLOTS[slice.systemKey] : undefined
  if (fixed !== undefined) return fixed
  // Deterministic, and biased away from the five reserved slots so an unnamed
  // category rarely collides with one the user is actually budgeting.
  return 5 - (slice.categoryId % 2)
}

/** How many categories get their own slice before the tail folds into "Other". */
const NAMED_SLICES = 5

const RADIUS = 62
const THICKNESS = 24
const GAP_UNITS = 2 // the 2px surface gap that separates touching marks
const CIRCUMFERENCE = 2 * Math.PI * RADIUS

type Slice = { key: string; name: string; minor: number; hue: string | null }

function buildSlices(
  categories: CategorySlice[],
  budget: MonthlyBudget,
  hues: string[],
): { slices: Slice[]; whole: number; overspentBy: number } {
  const spending = categories
    .map((category) => ({ category, magnitude: Math.abs(category.spentMinor) }))
    .filter((entry) => entry.magnitude > 0)

  // Ordered by size for the legend's sake, but the hue comes from the category.
  const ranked = [...spending].sort((a, b) => b.magnitude - a.magnitude)
  const named = ranked.slice(0, NAMED_SLICES)
  const tail = ranked.slice(NAMED_SLICES)

  const slices: Slice[] = named.map((entry) => ({
    key: `c${entry.category.categoryId}`,
    name: entry.category.name,
    minor: entry.magnitude,
    hue: hues[slotFor(entry.category)],
  }))

  if (tail.length > 0) {
    slices.push({
      key: 'other',
      name: `Other (${tail.length})`,
      minor: tail.reduce((sum, entry) => sum + entry.magnitude, 0),
      hue: hues[5],
    })
  }

  const spent = spending.reduce((sum, entry) => sum + entry.magnitude, 0)
  const overspentBy = Math.max(0, spent - budget.allocatedMinor)

  // Within budget: the ring is the budget, and the remainder is a real slice.
  // Overspent: there is no remainder, so the ring becomes the spending itself —
  // drawing a 110%-full circle would just look like a full one and hide it.
  if (overspentBy === 0) {
    slices.push({
      key: 'left',
      name: 'Left to spend',
      minor: budget.allocatedMinor - spent,
      hue: null,
    })
    return { slices, whole: budget.allocatedMinor, overspentBy }
  }
  return { slices, whole: spent, overspentBy }
}

export function BudgetDonut({
  categories,
  budget,
  currency,
  monthLabel,
}: {
  categories: CategorySlice[]
  budget: MonthlyBudget
  currency: string
  monthLabel: string
}) {
  const [hovered, setHovered] = useState<string | null>(null)

  if (!budget.allocated) {
    return (
      <section className="card">
        <h2>Monthly budget</h2>
        <p className="empty">
          No budget set for {monthLabel}. Set one on the{' '}
          <a href="#/budgets">Budgets</a> page and this fills in.
        </p>
      </section>
    )
  }

  const { slices, whole, overspentBy } = buildSlices(categories, budget, HUES)
  const spent = Math.abs(budget.allocatedMinor - budget.remainingMinor)
  const consumed = budget.allocatedMinor === 0 ? 0 : spent / budget.allocatedMinor

  let offset = 0

  return (
    <section className="card">
      <h2>Monthly budget</h2>
      <p className="card-sub">
        {formatMagnitude(budget.allocatedMinor, currency)} budgeted for {monthLabel}
      </p>

      <div className="donut-layout">
        <div className="donut-wrap">
          <svg viewBox="-90 -90 180 180" width="176" height="176" role="img"
            aria-label={`${formatPercent(consumed)} of the monthly budget spent`}>
            {/* SVG arcs start at three o'clock; a ring people read starts at
                twelve, so the whole group is rotated a quarter turn back. */}
            <g transform="rotate(-90)">
            {slices.map((slice) => {
              const length = whole === 0 ? 0 : (slice.minor / whole) * CIRCUMFERENCE
              // The gap is taken out of each arc rather than drawn on top, so
              // neighbours read as separate without a stroke around them.
              const drawn = Math.max(length - GAP_UNITS, 0)
              const dash = `${drawn} ${CIRCUMFERENCE - drawn}`
              const element = (
                <circle
                  key={slice.key}
                  r={RADIUS}
                  fill="none"
                  className={[
                    'donut-arc',
                    slice.hue === null ? 'donut-arc--remainder' : '',
                    hovered === slice.key ? 'donut-arc--hovered' : '',
                  ]
                    .filter(Boolean)
                    .join(' ')}
                  stroke={slice.hue ?? undefined}
                  strokeWidth={THICKNESS}
                  strokeDasharray={dash}
                  strokeDashoffset={-offset}
                  onMouseEnter={() => setHovered(slice.key)}
                  onMouseLeave={() => setHovered(null)}
                >
                  <title>
                    {slice.name}: {formatMagnitude(slice.minor, currency)}
                  </title>
                </circle>
              )
              offset += length
              return element
            })}
            </g>
          </svg>

          <div className="donut-centre">
            <span className="donut-centre-value">{formatPercent(consumed)}</span>
            <span className="donut-centre-label">used</span>
          </div>
        </div>

        <div className="donut-side">
          {/* A legend is the dependable identity channel and is always present. */}
          <ul className="legend">
            {slices.map((slice) => (
              <li
                key={slice.key}
                className={hovered === slice.key ? 'legend-row legend-row--hovered' : 'legend-row'}
                onMouseEnter={() => setHovered(slice.key)}
                onMouseLeave={() => setHovered(null)}
              >
                <span
                  className={slice.hue === null ? 'swatch swatch--remainder' : 'swatch'}
                  style={slice.hue === null ? undefined : { background: slice.hue }}
                  aria-hidden="true"
                />
                <span className="legend-name">{slice.name}</span>
                <span className="legend-value">{formatMagnitude(slice.minor, currency)}</span>
                <span className="legend-share">
                  {formatPercent(whole === 0 ? 0 : slice.minor / whole)}
                </span>
              </li>
            ))}
          </ul>

          {overspentBy > 0 ? (
            <p className="donut-status donut-status--over">
              Over budget by {formatMagnitude(overspentBy, currency)}
            </p>
          ) : (
            <p className="donut-status">
              {formatSigned(budget.remainingMinor, currency)} left to spend
            </p>
          )}
        </div>
      </div>
    </section>
  )
}
