import type { BudgetLine } from '../api'
import { formatMagnitude, formatPercent } from '../money'

/**
 * One meter per budget: spent against allocated.
 *
 * **The status is always written out, never carried by colour alone.** The
 * warning amber sits below a 3:1 contrast ratio on a white surface — it is
 * chosen for recognisability, not legibility — so every row states "On track",
 * "Close to limit" or "Over budget" in text beside a shaped icon. Anyone who
 * cannot separate the amber from the green reads the same information.
 *
 * The unfilled track is a light step of the fill's own hue, so the state reads
 * across the whole bar rather than only across the filled part.
 */

type Status = 'good' | 'warning' | 'critical'

/** Where "close to the limit" begins. */
const NEAR_LIMIT = 0.85

function statusFor(consumed: number): Status {
  if (consumed > 1) return 'critical'
  if (consumed >= NEAR_LIMIT) return 'warning'
  return 'good'
}

const STATUS_TEXT: Record<Status, string> = {
  good: 'On track',
  warning: 'Close to limit',
  critical: 'Over budget',
}

/**
 * Shapes, not just colours: a tick, an exclamation and a cross are separable in
 * grayscale and under any form of colour blindness.
 */
const STATUS_ICON: Record<Status, string> = {
  good: 'M2 6.2 4.6 8.8 10 3',
  warning: 'M6 2v5M6 9.2v.6',
  critical: 'M2.5 2.5 9.5 9.5M9.5 2.5 2.5 9.5',
}

function Meter({ budget, currency }: { budget: BudgetLine; currency: string }) {
  const spent = Math.abs(budget.spentMinor)
  const allocated = Math.abs(budget.allocatedMinor)
  // An unset allocation would divide by zero; treat it as nothing consumed
  // rather than as infinitely overspent.
  const consumed = allocated === 0 ? 0 : spent / allocated
  const status = statusFor(consumed)
  const remaining = allocated - spent

  return (
    <li className="meter-row">
      <div className="meter-head">
        <span className="meter-name">{budget.categoryName}</span>
        <span className={`meter-status meter-status--${status}`}>
          <svg viewBox="0 0 12 12" width="12" height="12" aria-hidden="true">
            <path
              d={STATUS_ICON[status]}
              fill="none"
              stroke="currentColor"
              strokeWidth="1.8"
              strokeLinecap="round"
            />
          </svg>
          {STATUS_TEXT[status]}
        </span>
      </div>

      <div
        className={`meter-track meter-track--${status}`}
        role="progressbar"
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={Math.round(consumed * 100)}
        aria-label={`${budget.categoryName}: ${formatPercent(consumed)} of budget used`}
      >
        <div
          className="meter-fill"
          // Capped at 100% so an overspent budget fills the bar rather than
          // overflowing its container; the number beside it carries the excess.
          style={{ width: `${Math.min(consumed, 1) * 100}%` }}
        />
      </div>

      <div className="meter-foot">
        <span>
          {formatMagnitude(spent, currency)} of {formatMagnitude(allocated, currency)} ·{' '}
          {formatPercent(consumed)} used
        </span>
        <span className={remaining < 0 ? 'over' : 'muted'}>
          {remaining < 0
            ? `${formatMagnitude(remaining, currency)} over`
            : `${formatMagnitude(remaining, currency)} left`}
        </span>
      </div>
    </li>
  )
}

export function BudgetProgress({
  budgets,
  currency,
}: {
  budgets: BudgetLine[]
  currency: string
}) {
  return (
    <section className="card">
      <h2>Budget progress</h2>
      {budgets.length === 0 ? (
        <p className="empty">No budgets set for this month.</p>
      ) : (
        <ul className="meters">
          {budgets.map((budget) => (
            <Meter key={budget.id} budget={budget} currency={currency} />
          ))}
        </ul>
      )}
    </section>
  )
}
