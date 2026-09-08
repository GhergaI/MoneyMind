import type { ReactNode } from 'react'

import { ROUTES, hrefFor, type RouteId } from './routes'

/**
 * Stroke-based glyphs at a common 20px box and 1.6 stroke weight, so the set
 * reads as one family. `currentColor` lets the active and hover states colour
 * the icon with the label instead of needing a second copy of each path.
 */
const ICONS: Record<RouteId, ReactNode> = {
  dashboard: (
    <>
      <rect x="3" y="3" width="7" height="7" rx="1.5" />
      <rect x="12" y="3" width="5" height="4" rx="1.5" />
      <rect x="12" y="9" width="5" height="8" rx="1.5" />
      <rect x="3" y="12" width="7" height="5" rx="1.5" />
    </>
  ),
  transactions: (
    <>
      <path d="M3 6h11" />
      <path d="M11 3 14 6l-3 3" />
      <path d="M17 14H6" />
      <path d="M9 11 6 14l3 3" />
    </>
  ),
  budgets: (
    <>
      <circle cx="10" cy="10" r="7" />
      <path d="M10 3v7l5 3" />
    </>
  ),
  goals: (
    <>
      <circle cx="10" cy="10" r="7" />
      <circle cx="10" cy="10" r="3" />
      <path d="M10 3v2M10 15v2M3 10h2M15 10h2" />
    </>
  ),
  insights: (
    <>
      <path d="M8 16h4" />
      <path d="M9 18h2" />
      <path d="M10 3a5 5 0 0 0-3 9v2h6v-2a5 5 0 0 0-3-9Z" />
    </>
  ),
  settings: (
    <>
      <circle cx="10" cy="10" r="2.5" />
      <path d="M10 2.5v2M10 15.5v2M4.7 4.7l1.4 1.4M13.9 13.9l1.4 1.4M2.5 10h2M15.5 10h2M4.7 15.3l1.4-1.4M13.9 6.1l1.4-1.4" />
    </>
  ),
}

export function Sidebar({ current }: { current: RouteId }) {
  return (
    <nav className="sidebar" aria-label="Sections">
      <div className="brand">
        <span className="brand-mark" aria-hidden="true">
          <svg viewBox="0 0 20 20" width="20" height="20">
            <path
              d="M3 15V7l4 4 3-6 3 5 4-3v8Z"
              fill="none"
              stroke="currentColor"
              strokeWidth="1.6"
              strokeLinejoin="round"
              strokeLinecap="round"
            />
          </svg>
        </span>
        <span className="brand-name">MoneyMind</span>
      </div>

      <ul className="nav-list">
        {ROUTES.map((route) => {
          const active = route.id === current
          return (
            <li key={route.id}>
              <a
                href={hrefFor(route.id)}
                className={active ? 'nav-link nav-link--active' : 'nav-link'}
                // The styling alone would leave the current section unannounced.
                aria-current={active ? 'page' : undefined}
              >
                <svg
                  viewBox="0 0 20 20"
                  width="20"
                  height="20"
                  aria-hidden="true"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="1.6"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                >
                  {ICONS[route.id]}
                </svg>
                {route.label}
              </a>
            </li>
          )
        })}
      </ul>
    </nav>
  )
}
