import { useEffect, useState } from 'react'

/**
 * Navigation is hash-based (`#/budgets`) rather than path-based.
 *
 * The built UI is served as static files from inside the jar. With path-based
 * routes, a reload on `/budgets` asks Spring Boot for a resource that does not
 * exist and gets a 404 — working around that needs a forwarding controller that
 * has to know every client route. A hash never reaches the server, so deep
 * links and reloads work identically from the dev server and the packaged jar
 * with no backend involvement at all.
 */

export const ROUTES = [
  { id: 'dashboard', label: 'Dashboard' },
  { id: 'transactions', label: 'Transactions' },
  { id: 'budgets', label: 'Budgets' },
  { id: 'goals', label: 'Goals' },
  { id: 'insights', label: 'Insights' },
  { id: 'settings', label: 'Settings' },
] as const

export type RouteId = (typeof ROUTES)[number]['id']

const DEFAULT_ROUTE: RouteId = 'dashboard'

function readHash(): RouteId {
  const id = window.location.hash.replace(/^#\/?/, '')
  return ROUTES.some((route) => route.id === id) ? (id as RouteId) : DEFAULT_ROUTE
}

/** The current route, kept in step with back/forward and manual URL edits. */
export function useHashRoute(): RouteId {
  const [route, setRoute] = useState<RouteId>(readHash)

  useEffect(() => {
    const onChange = () => setRoute(readHash())
    window.addEventListener('hashchange', onChange)
    // The hash may have changed between the initial render and this effect
    // running — re-read rather than trust the first value.
    onChange()
    return () => window.removeEventListener('hashchange', onChange)
  }, [])

  return route
}

export function hrefFor(id: RouteId): string {
  return `#/${id}`
}
