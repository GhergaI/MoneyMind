import type { RouteId } from '../nav/routes'

/**
 * Honest placeholders for the sections the sidebar reaches but that have no
 * screen yet.
 *
 * Each one names the phase it belongs to rather than saying "coming soon" and
 * leaving the reader guessing whether the feature is missing or broken. The
 * dashboard already shows read-only budget and goal panels; these pages are
 * where editing them will live.
 */
type PlaceholderRoute = Exclude<RouteId, 'dashboard' | 'transactions' | 'budgets'>

const COPY: Record<PlaceholderRoute, { title: string; body: string }> = {
  goals: {
    title: 'Goals',
    body: 'Creating and funding savings goals comes with Phase 3. Progress against existing goals is shown on the dashboard.',
  },
  insights: {
    title: 'Insights',
    body: 'A fuller insight feed — recurring payments, price rises, subscriptions you may have forgotten — arrives in Phase 5. The three headline insights are on the dashboard.',
  },
  settings: {
    title: 'Settings',
    body: 'Accounts, categories and the default currency become editable here. For now they are configured in application.yml and the first-run bootstrap.',
  },
}

export function ComingSoon({ route }: { route: PlaceholderRoute }) {
  const { title, body } = COPY[route]
  return (
    <>
      <header className="page-head">
        <div>
          <h1>{title}</h1>
          <p className="tagline">Not built yet</p>
        </div>
      </header>
      <section className="card">
        <p className="placeholder">{body}</p>
      </section>
    </>
  )
}
