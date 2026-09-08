import { useState } from 'react'

import type { TrendPoint } from '../api'
import { formatCompact, formatMagnitude, fractionDigits } from '../money'

/**
 * Six months of spending, as columns.
 *
 * **The current month is partial and is drawn as partial.** Four days of
 * September beside a complete August would otherwise render as a dramatic
 * collapse in spending — the single most misleading thing a month-over-month
 * chart can do. The final column is therefore visibly lighter, labelled "so
 * far", and excluded from the "highest month" comparison.
 *
 * Values are labelled selectively: the current month, because it is the one the
 * reader came for. The rest are carried by the y-axis, the hover tooltip, and
 * the table underneath, rather than by a number stamped on every column.
 */

const VIEW_WIDTH = 620
const VIEW_HEIGHT = 210
const GUTTER_LEFT = 54
const GUTTER_BOTTOM = 30
const GUTTER_TOP = 26
const MAX_BAR_WIDTH = 28
const CORNER = 4

/** Rounds an axis maximum up to a readable number: 1, 2, 2.5 or 5 × a power of ten. */
function niceCeiling(value: number): number {
  if (value <= 0) return 1
  const magnitude = 10 ** Math.floor(Math.log10(value))
  for (const step of [1, 2, 2.5, 5]) {
    if (value <= step * magnitude) return step * magnitude
  }
  return 10 * magnitude
}

/**
 * A column with its top corners rounded and its base square, so the mark reads
 * as growing from the baseline rather than floating.
 */
function columnPath(x: number, y: number, width: number, height: number): string {
  const radius = Math.min(CORNER, width / 2, Math.max(height, 0))
  if (height <= 0) return ''
  return [
    `M ${x} ${y + height}`,
    `L ${x} ${y + radius}`,
    `Q ${x} ${y} ${x + radius} ${y}`,
    `L ${x + width - radius} ${y}`,
    `Q ${x + width} ${y} ${x + width} ${y + radius}`,
    `L ${x + width} ${y + height}`,
    'Z',
  ].join(' ')
}

export function SpendingTrend({
  trend,
  currency,
  currentMonth,
}: {
  trend: TrendPoint[]
  currency: string
  currentMonth: string
}) {
  const [hovered, setHovered] = useState<number | null>(null)

  if (trend.length === 0) return null

  const magnitudes = trend.map((point) => Math.abs(point.spentMinor))

  // Geometry stays in minor units; only the axis ceiling is rounded, and that
  // is done in major units so the ticks land on numbers a person would write.
  const scale = 10 ** fractionDigits(currency)
  const axisMax = niceCeiling(Math.max(...magnitudes, 1) / scale) * scale

  const plotWidth = VIEW_WIDTH - GUTTER_LEFT
  const plotHeight = VIEW_HEIGHT - GUTTER_TOP - GUTTER_BOTTOM
  const band = plotWidth / trend.length
  const barWidth = Math.min(MAX_BAR_WIDTH, band * 0.55)

  const ticks = [0, 0.5, 1].map((fraction) => ({
    fraction,
    value: axisMax * fraction,
    y: GUTTER_TOP + plotHeight * (1 - fraction),
  }))

  // The completed months only — a partial month is not a candidate for "highest".
  const completed = trend.filter((point) => point.month !== currentMonth)
  const highest = completed.reduce<TrendPoint | null>(
    (best, point) =>
      best === null || Math.abs(point.spentMinor) > Math.abs(best.spentMinor) ? point : best,
    null,
  )

  return (
    <section className="card">
      <h2>Spending trend</h2>
      <p className="card-sub">
        Last {trend.length} months
        {highest ? ` · highest was ${highest.label} at ${formatMagnitude(highest.spentMinor, currency)}` : ''}
      </p>

      <div className="chart-wrap">
        <svg
          viewBox={`0 0 ${VIEW_WIDTH} ${VIEW_HEIGHT}`}
          width="100%"
          className="chart"
          role="img"
          aria-label={`Monthly spending for the last ${trend.length} months`}
        >
          {ticks.map((tick) => (
            <g key={tick.fraction}>
              <line
                x1={GUTTER_LEFT}
                x2={VIEW_WIDTH}
                y1={tick.y}
                y2={tick.y}
                className="grid-line"
              />
              <text x={GUTTER_LEFT - 10} y={tick.y + 4} className="axis-text" textAnchor="end">
                {formatCompact(tick.value, currency)}
              </text>
            </g>
          ))}

          {trend.map((point, index) => {
            const magnitude = Math.abs(point.spentMinor)
            const height = axisMax === 0 ? 0 : (magnitude / axisMax) * plotHeight
            const x = GUTTER_LEFT + band * index + (band - barWidth) / 2
            const y = GUTTER_TOP + plotHeight - height
            const partial = point.month === currentMonth
            return (
              <g
                key={point.month}
                onMouseEnter={() => setHovered(index)}
                onMouseLeave={() => setHovered(null)}
              >
                {/* A full-height hit area: the column itself is a poor target
                    when the month's spending is small. */}
                <rect
                  x={GUTTER_LEFT + band * index}
                  y={GUTTER_TOP}
                  width={band}
                  height={plotHeight}
                  fill="transparent"
                />
                {/* Always present, not rendered on hover: the browser reads
                    <title> as the element's accessible name and its native
                    tooltip, and neither works if it only appears afterwards. */}
                <title>
                  {point.label}
                  {partial ? ' so far' : ''}: {formatMagnitude(magnitude, currency)}
                </title>
                <path
                  d={columnPath(x, y, barWidth, height)}
                  className={[
                    'column',
                    partial ? 'column--partial' : '',
                    hovered === index ? 'column--hovered' : '',
                  ]
                    .filter(Boolean)
                    .join(' ')}
                />
                {partial && (
                  <text
                    x={x + barWidth / 2}
                    y={y - 8}
                    className="column-label"
                    textAnchor="middle"
                  >
                    {formatCompact(magnitude, currency)}
                  </text>
                )}
                <text
                  x={GUTTER_LEFT + band * index + band / 2}
                  y={VIEW_HEIGHT - 10}
                  className={partial ? 'axis-text axis-text--current' : 'axis-text'}
                  textAnchor="middle"
                >
                  {point.label}
                  {partial ? ' ·' : ''}
                </text>
              </g>
            )
          })}

          <line
            x1={GUTTER_LEFT}
            x2={VIEW_WIDTH}
            y1={GUTTER_TOP + plotHeight}
            y2={GUTTER_TOP + plotHeight}
            className="axis-line"
          />
        </svg>
      </div>

      <p className="footnote">· {monthName(trend, currentMonth)} is still in progress, so far</p>

      <details className="numbers">
        <summary>Show the numbers</summary>
        <table className="mini-table">
          <thead>
            <tr>
              <th scope="col">Month</th>
              <th scope="col" className="right">
                Spent
              </th>
            </tr>
          </thead>
          <tbody>
            {trend.map((point) => (
              <tr key={point.month}>
                <th scope="row">
                  {point.label}
                  {point.month === currentMonth ? ' (so far)' : ''}
                </th>
                <td className="right">{formatMagnitude(point.spentMinor, currency)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </details>
    </section>
  )
}

function monthName(trend: TrendPoint[], currentMonth: string): string {
  return trend.find((point) => point.month === currentMonth)?.label ?? 'This month'
}
