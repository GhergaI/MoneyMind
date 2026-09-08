import type { GoalLine } from '../api'
import { formatMagnitude, formatPercent } from '../money'

/**
 * Savings goals with their progress.
 *
 * The meter track is a light step of the accent's own hue rather than a neutral
 * gray, so a nearly-empty goal still reads as a goal. Progress above 100% is
 * legitimate — over-saving is not an error — so the number is shown uncapped
 * while the bar stops at full.
 */
export function SavingsPanel({ goals, currency }: { goals: GoalLine[]; currency: string }) {
  return (
    <section className="card">
      <h2>Savings goals</h2>
      {goals.length === 0 ? (
        <p className="empty">No goals yet.</p>
      ) : (
        <ul className="goals">
          {goals.map((goal) => {
            const reached = goal.targetMinor === 0 ? 0 : goal.savedMinor / goal.targetMinor
            return (
              <li key={goal.id} className="goal">
                <div className="goal-head">
                  <span className="goal-name">{goal.name}</span>
                  <span className="goal-pct">{formatPercent(reached)}</span>
                </div>
                <div
                  className="goal-track"
                  role="progressbar"
                  aria-valuemin={0}
                  aria-valuemax={100}
                  aria-valuenow={Math.round(reached * 100)}
                  aria-label={`${goal.name}: ${formatPercent(reached)} of target saved`}
                >
                  <div
                    className="goal-fill"
                    style={{ width: `${Math.min(Math.max(reached, 0), 1) * 100}%` }}
                  />
                </div>
                <div className="goal-foot">
                  <span>
                    {formatMagnitude(goal.savedMinor, currency)} of{' '}
                    {formatMagnitude(goal.targetMinor, currency)}
                  </span>
                  {goal.targetDate && <span className="muted">by {goal.targetDate}</span>}
                </div>
              </li>
            )
          })}
        </ul>
      )}
    </section>
  )
}
