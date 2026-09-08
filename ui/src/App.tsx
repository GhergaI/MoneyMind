import { useCallback, useEffect, useState } from 'react'

import { fetchDashboard, type Dashboard as DashboardData } from './api'
import { Sidebar } from './nav/Sidebar'
import { useHashRoute } from './nav/routes'
import { Budgets } from './pages/Budgets'
import { ComingSoon } from './pages/ComingSoon'
import { Dashboard } from './pages/Dashboard'
import { Transactions } from './pages/Transactions'

/**
 * The app shell: a fixed sidebar and one routed pane.
 *
 * The dashboard is fetched here rather than inside the page, so every figure on
 * screen comes from one request and therefore one server-side transaction — a
 * balance can never disagree with the transactions that explain it.
 *
 * `reload` is handed to the pages that write. After adding a transaction or
 * changing a budget, the dashboard is stale, and refetching it centrally is
 * what keeps the donut and the tiles honest the moment you switch back to them.
 */
export default function App() {
  const route = useHashRoute()
  const [data, setData] = useState<DashboardData | null>(null)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async () => {
    try {
      setData(await fetchDashboard())
      setError(null)
    } catch (cause: unknown) {
      setError(cause instanceof Error ? cause.message : String(cause))
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  const reload = useCallback(() => {
    void load()
  }, [load])

  return (
    <div className="layout">
      <Sidebar current={route} />
      <main className="pane">
        {error && (
          <section className="card card--error">
            <h2>Backend unreachable</h2>
            <p>
              <code>{error}</code>
            </p>
            <p className="hint">
              Start it with <code>./run.sh dev</code> on port 8080.
            </p>
          </section>
        )}

        {route === 'transactions' && <Transactions onChanged={reload} />}
        {route === 'budgets' && <Budgets onChanged={reload} />}

        {route === 'dashboard' &&
          (data ? <Dashboard data={data} /> : !error && <p className="muted">Loading…</p>)}

        {route !== 'dashboard' && route !== 'transactions' && route !== 'budgets' && (
          <ComingSoon route={route} />
        )}
      </main>
    </div>
  )
}
