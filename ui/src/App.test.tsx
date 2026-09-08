import { render, screen, within } from '@testing-library/react'

import App from './App'
import { dashboard, stubApi } from './test-fixtures'

describe('App', () => {
  beforeEach(() => {
    window.location.hash = ''
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('shows every section of the dashboard', async () => {
    vi.stubGlobal('fetch', stubApi())
    render(<App />)

    expect(await screen.findByRole('heading', { name: 'Dashboard', level: 1 })).toBeInTheDocument()
    for (const section of [
      'Monthly budget',
      'Spending by category',
      'Spending trend',
      'Budget progress',
      'Savings goals',
      'Recent transactions',
      'Money Insights',
    ]) {
      expect(screen.getByRole('heading', { name: section })).toBeInTheDocument()
    }
  })

  it('offers every sidebar section', async () => {
    vi.stubGlobal('fetch', stubApi())
    render(<App />)

    const nav = await screen.findByRole('navigation', { name: 'Sections' })
    for (const label of ['Dashboard', 'Transactions', 'Budgets', 'Goals', 'Insights', 'Settings']) {
      expect(within(nav).getByRole('link', { name: label })).toBeInTheDocument()
    }
  })

  it('routes to a section from the hash', async () => {
    vi.stubGlobal('fetch', stubApi())
    window.location.hash = '#/budgets'
    render(<App />)

    expect(await screen.findByRole('heading', { name: 'Budgets', level: 1 })).toBeInTheDocument()
  })

  it('reads the headline figures from minor units', async () => {
    vi.stubGlobal('fetch', stubApi())
    render(<App />)

    // Scoped to the tile row: the same figures legitimately appear again below,
    // so an unscoped query matches more than one node.
    const tiles = await screen.findByRole('region', { name: 'Headline figures' })

    // 384210 minor units of a 2-digit currency is 3,842.10 — never 384,210.
    expect(within(tiles).getByText('€3,842.10')).toBeInTheDocument()
    // Spending is stored negative and shown as a magnitude beside "Spent".
    expect(within(tiles).getByText('€1,277.60')).toBeInTheDocument()
    // Net worth keeps its sign and is lower than the balance by the card debt.
    expect(within(tiles).getByText('€3,592.10')).toBeInTheDocument()
  })

  it('marks a transfer as neither income nor spending', async () => {
    vi.stubGlobal('fetch', stubApi())
    render(<App />)

    const transferRow = (await screen.findByText('Monthly saving')).closest('tr')
    expect(transferRow).not.toBeNull()
    expect(within(transferRow as HTMLElement).getByText('Transfer')).toBeInTheDocument()
    // The neutral tone is what keeps it from reading as an expense.
    expect(within(transferRow as HTMLElement).getByText('-€400.00')).toHaveClass('amount--neutral')
  })

  it('states an overspent budget in words, not only in colour', async () => {
    vi.stubGlobal('fetch', stubApi())
    render(<App />)

    // Household: 300.00 spent against a 250.00 allocation.
    expect(await screen.findByText('Over budget')).toBeInTheDocument()
    expect(screen.getByText('On track')).toBeInTheDocument()
  })

  it('says so rather than silently reporting one currency of several', async () => {
    vi.stubGlobal('fetch', stubApi({ dashboard: dashboard({ otherCurrencies: ['GBP'] }) }))
    render(<App />)

    expect(await screen.findByText(/You also hold GBP/)).toBeInTheDocument()
  })

  it('explains how to start the backend when it is unreachable', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 503 }))
    render(<App />)

    expect(await screen.findByText('Backend unreachable')).toBeInTheDocument()
  })
})
