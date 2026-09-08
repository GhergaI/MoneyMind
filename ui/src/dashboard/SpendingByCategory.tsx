import { useState } from 'react'

import type { CategorySlice } from '../api'
import { formatMagnitude, formatPercent } from '../money'

/**
 * Spending by category, as horizontal bars.
 *
 * A bar chart rather than a pie: the reader's job here is to compare
 * magnitudes, which is length — pies make that an angle-estimation exercise and
 * need a colour per slice to boot. Horizontal because category names are words
 * of very different lengths, and rotated axis labels are unreadable.
 *
 * **One hue for every bar.** Length already encodes the magnitude; grading the
 * colour by size too would encode it twice and imply the colours mean something
 * they do not. With a single series there is nothing to tell apart, so there is
 * no legend either — the heading says what is plotted.
 *
 * Every bar carries its name, amount and share as text, so nothing here is
 * available only through colour or only on hover.
 */
export function SpendingByCategory({
  categories,
  currency,
}: {
  categories: CategorySlice[]
  currency: string
}) {
  const [hovered, setHovered] = useState<number | null>(null)

  if (categories.length === 0) {
    return (
      <section className="card">
        <h2>Spending by category</h2>
        <p className="empty">No spending recorded this month yet.</p>
      </section>
    )
  }

  const magnitudes = categories.map((category) => Math.abs(category.spentMinor))
  const total = magnitudes.reduce((sum, value) => sum + value, 0)
  // Scale to the largest bar, not to the total: scaling to the total leaves
  // every bar short and the comparison between them cramped into a fraction of
  // the width.
  const largest = Math.max(...magnitudes)

  return (
    <section className="card">
      <h2>Spending by category</h2>
      <ul className="bars">
        {categories.map((category, index) => {
          const magnitude = magnitudes[index]
          const share = total === 0 ? 0 : magnitude / total
          return (
            <li
              key={category.categoryId}
              className={hovered === index ? 'bar-row bar-row--hovered' : 'bar-row'}
              onMouseEnter={() => setHovered(index)}
              onMouseLeave={() => setHovered(null)}
              onFocus={() => setHovered(index)}
              onBlur={() => setHovered(null)}
              tabIndex={0}
            >
              <span className="bar-name">{category.name}</span>
              <span className="bar-track">
                <span
                  className="bar-fill"
                  style={{ width: `${(magnitude / largest) * 100}%` }}
                />
              </span>
              <span className="bar-value">{formatMagnitude(magnitude, currency)}</span>
              <span className="bar-share">{formatPercent(share)}</span>
            </li>
          )
        })}
      </ul>
    </section>
  )
}
