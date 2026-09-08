import type { Dashboard } from '../api'
import { formatMagnitude, formatSigned } from '../money'

/**
 * The headline row.
 *
 * Total balance is the one hero figure on the page; the rest are stat tiles.
 * Exactly one number is allowed to be that large — a page where everything
 * shouts has no lead.
 *
 * Signs: spending and income are shown as magnitudes beside a word that carries
 * the direction ("spent", "in"), which reads better than a minus sign. Net
 * worth keeps its sign, because a negative net worth is real information and
 * hiding it would be dishonest.
 */

type Delta = { text: string; direction: 'good' | 'bad' | 'flat' }

/**
 * The spending delta, read out of the month-over-month insight's *facts* rather
 * than its prose — the sentence is reworded by the optional LLM layer, the
 * facts never are.
 */
function spendingDelta(dashboard: Dashboard): Delta | undefined {
  const insight = dashboard.insights.find((card) => card.code === 'SPENDING_VS_LAST_MONTH')
  if (!insight) return undefined

  const change = Number(insight.facts.changePercent)
  const days = Number(insight.facts.comparedDays)
  if (!Number.isFinite(change) || !Number.isFinite(days)) return undefined

  if (change === 0) return { text: `level with last month`, direction: 'flat' }
  const direction = change < 0 ? 'good' : 'bad'
  const word = change < 0 ? 'less' : 'more'
  return {
    text: `${Math.abs(change)}% ${word} than the same ${days} days last month`,
    direction,
  }
}

function Tile({
  label,
  value,
  note,
  delta,
  hero = false,
}: {
  label: string
  value: string
  note?: string
  delta?: Delta
  hero?: boolean
}) {
  return (
    <div className={hero ? 'tile tile--hero' : 'tile'}>
      <div className="tile-label">{label}</div>
      <div className={hero ? 'tile-value tile-value--hero' : 'tile-value'}>{value}</div>
      {delta && <div className={`tile-delta tile-delta--${delta.direction}`}>{delta.text}</div>}
      {note && !delta && <div className="tile-note">{note}</div>}
    </div>
  )
}

export function StatTiles({ dashboard }: { dashboard: Dashboard }) {
  const { currency, totals } = dashboard
  const owed = totals.totalBalanceMinor - totals.netWorthMinor

  return (
    <section className="tiles" aria-label="Headline figures">
      <Tile
        hero
        label="Total balance"
        value={formatSigned(totals.totalBalanceMinor, currency)}
        note="Across every account except what you owe"
      />
      <Tile
        label={`Income in ${dashboard.monthLabel.split(' ')[0]}`}
        value={formatMagnitude(totals.monthIncomeMinor, currency)}
        note="Transfers between your accounts excluded"
      />
      <Tile
        label={`Spent in ${dashboard.monthLabel.split(' ')[0]}`}
        value={formatMagnitude(totals.monthSpendMinor, currency)}
        delta={spendingDelta(dashboard)}
        note="Refunds already deducted"
      />
      <Tile
        label="Savings"
        value={formatMagnitude(totals.savingsMinor, currency)}
        note="Balance of your savings accounts"
      />
      <Tile
        label="Net worth"
        value={formatSigned(totals.netWorthMinor, currency)}
        note={
          owed > 0
            ? `After ${formatMagnitude(owed, currency)} owed on credit`
            : 'Nothing owed on credit'
        }
      />
    </section>
  )
}
