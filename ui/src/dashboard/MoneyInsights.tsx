import type { InsightCard, InsightTone } from '../api'

/**
 * The "Money Insights" strip.
 *
 * Each card is produced by a deterministic rule on the server. The sentence is
 * a rendering of the rule's facts, and the facts travel alongside it — so when
 * the optional LLM layer is switched on and rewords the sentence, the figure
 * shown underneath is still the computed one rather than something parsed back
 * out of prose. With the LLM off, which is the default, the template sentence
 * is what ships.
 *
 * The tone drives a colour and a shape and nothing else. It never changes a
 * number, and an empty list is a legitimate outcome: three months of data
 * genuinely supports no confident observation, and inventing one to fill the
 * strip is how a finance app loses trust.
 */

const TONE_ICON: Record<InsightTone, string> = {
  // A rising line, a flat line, and an exclamation: separable without colour.
  POSITIVE: 'M2 11 6 6l3 2 5-6',
  NEUTRAL: 'M2 8h12',
  ATTENTION: 'M8 3v6M8 11.2v.6',
}

/**
 * The one figure worth repeating under each sentence, read from `facts`.
 * Returning nothing is fine — the sentence already stands on its own.
 */
function highlight(card: InsightCard): string | undefined {
  switch (card.code) {
    case 'SPENDING_VS_LAST_MONTH': {
      const change = Number(card.facts.changePercent)
      if (!Number.isFinite(change)) return undefined
      return `${change > 0 ? '+' : ''}${change}% vs last month`
    }
    case 'TOP_SPENDING_CATEGORY':
      return card.facts.sharePercent ? `${card.facts.sharePercent}% of spending` : undefined
    case 'SAVINGS_GOAL_PACE':
      return card.facts.reachedPercent ? `${card.facts.reachedPercent}% saved` : undefined
    default:
      return undefined
  }
}

export function MoneyInsights({ insights }: { insights: InsightCard[] }) {
  return (
    <section className="card">
      <h2>Money Insights</h2>
      {insights.length === 0 ? (
        <p className="empty">
          Not enough history yet — insights appear once there is a month to compare against.
        </p>
      ) : (
        <ul className="insights">
          {insights.map((card) => {
            const tone = card.tone.toLowerCase()
            const figure = highlight(card)
            return (
              <li key={card.code} className={`insight insight--${tone}`}>
                <span className="insight-icon" aria-hidden="true">
                  <svg viewBox="0 0 16 16" width="16" height="16">
                    <path
                      d={TONE_ICON[card.tone]}
                      fill="none"
                      stroke="currentColor"
                      strokeWidth="1.8"
                      strokeLinecap="round"
                      strokeLinejoin="round"
                    />
                  </svg>
                </span>
                <p className="insight-text">{card.message}</p>
                {figure && <span className="insight-figure">{figure}</span>}
              </li>
            )
          })}
        </ul>
      )}
    </section>
  )
}
