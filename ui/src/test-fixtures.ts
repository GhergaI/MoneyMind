import type {
  AccountOption,
  BudgetView,
  CategoryOption,
  Dashboard,
  TransactionPage,
} from './api'

/**
 * Shared fixtures shaped like the real payloads.
 *
 * They deliberately include the cases that break naive money handling: a
 * transfer among the ledger rows, a refund recorded as a positive amount in an
 * expense category, and an overspent budget.
 */

export function dashboard(overrides: Partial<Dashboard> = {}): Dashboard {
  return {
    currency: 'EUR',
    month: '2026-09',
    monthLabel: 'September 2026',
    today: '2026-09-07',
    otherCurrencies: [],
    totals: {
      totalBalanceMinor: 384210,
      netWorthMinor: 359210,
      savingsMinor: 240000,
      monthIncomeMinor: 320000,
      monthSpendMinor: -127760,
    },
    monthlyBudget: {
      allocatedMinor: 200000,
      allocated: true,
      remainingMinor: 72240,
    },
    accounts: [
      { id: 1, name: 'Main', type: 'CURRENT', balanceMinor: 144210, asset: true },
      { id: 2, name: 'Savings', type: 'SAVINGS', balanceMinor: 240000, asset: true },
      { id: 3, name: 'Credit card', type: 'CREDIT_CARD', balanceMinor: -25000, asset: false },
    ],
    spendingByCategory: [
      { categoryId: 20, name: 'Food', systemKey: 'FOOD', spentMinor: -80000 },
      { categoryId: 21, name: 'Household', systemKey: 'HOUSEHOLD', spentMinor: -30000 },
      { categoryId: 9, name: 'Health', systemKey: 'HEALTH', spentMinor: -17760 },
    ],
    spendingTrend: [
      { month: '2026-08', label: 'Aug', spentMinor: -210000 },
      { month: '2026-09', label: 'Sep', spentMinor: -127760 },
    ],
    budgets: [
      {
        id: 1,
        categoryName: 'Food',
        allocatedMinor: 100000,
        spentMinor: -80000,
        startsOn: '2026-09-01',
        endsOn: '2026-09-30',
      },
      {
        id: 2,
        categoryName: 'Household',
        allocatedMinor: 25000,
        spentMinor: -30000,
        startsOn: '2026-09-01',
        endsOn: '2026-09-30',
      },
    ],
    recentTransactions: [
      {
        id: 9,
        bookedOn: '2026-09-06',
        description: 'Monthly saving',
        kind: 'TRANSFER',
        accountName: 'Main',
        categoryName: null,
        amountMinor: -40000,
      },
      {
        id: 8,
        bookedOn: '2026-09-05',
        description: 'Returned item',
        kind: 'EXPENSE',
        accountName: 'Credit card',
        categoryName: 'Household',
        amountMinor: 3800,
      },
    ],
    savingsGoals: [
      {
        id: 1,
        name: 'Emergency fund',
        targetMinor: 600000,
        savedMinor: 240000,
        targetDate: '2027-05-07',
      },
    ],
    insights: [
      {
        code: 'SPENDING_VS_LAST_MONTH',
        tone: 'POSITIVE',
        message: "You're spending less than last month.",
        facts: { changePercent: '-12', comparedDays: '7' },
      },
      {
        code: 'TOP_SPENDING_CATEGORY',
        tone: 'NEUTRAL',
        message: 'Food is your highest spending category.',
        facts: { sharePercent: '63' },
      },
    ],
    ...overrides,
  }
}

export function budgetView(overrides: Partial<BudgetView> = {}): BudgetView {
  return {
    currency: 'EUR',
    month: '2026-09',
    allocatedMinor: 200000,
    allocated: true,
    inherited: false,
    spentMinor: -127760,
    remainingMinor: 72240,
    categories: [
      { id: 20, name: 'Food', systemKey: 'FOOD', allocatedMinor: 100000, spentMinor: -80000 },
      {
        id: 21,
        name: 'Household',
        systemKey: 'HOUSEHOLD',
        allocatedMinor: 25000,
        spentMinor: -30000,
      },
      // Not budgeted at all, which is different from budgeted zero.
      { id: 9, name: 'Health', systemKey: 'HEALTH', allocatedMinor: null, spentMinor: -17760 },
    ],
    ...overrides,
  }
}

export function categories(): CategoryOption[] {
  return [
    { id: 1, name: 'Salary', systemKey: 'SALARY', kind: 'INCOME' },
    { id: 20, name: 'Food', systemKey: 'FOOD', kind: 'EXPENSE' },
    { id: 21, name: 'Household', systemKey: 'HOUSEHOLD', kind: 'EXPENSE' },
    { id: 9, name: 'Health', systemKey: 'HEALTH', kind: 'EXPENSE' },
  ]
}

export function accounts(): AccountOption[] {
  return [
    { id: 1, name: 'Main', type: 'CURRENT', currency: 'EUR', balanceMinor: 144210 },
    { id: 2, name: 'Savings', type: 'SAVINGS', currency: 'EUR', balanceMinor: 240000 },
  ]
}

export function transactionPage(overrides: Partial<TransactionPage> = {}): TransactionPage {
  return {
    currency: 'EUR',
    month: '2026-09',
    entries: dashboard().recentTransactions,
    ...overrides,
  }
}

export type StubRoutes = {
  dashboard?: Dashboard
  budget?: BudgetView
  transactions?: TransactionPage
  /** Records every write the UI makes, so tests can assert on the payload. */
  writes?: { url: string; method: string; body: unknown }[]
}

/**
 * A fetch stub that answers by URL.
 *
 * Routing matters now that pages fetch their own data: a stub that returns the
 * dashboard for every request feeds the Budgets page a payload with no
 * `categories`, and the page dies on a field that is simply absent.
 */
export function stubApi(routes: StubRoutes = {}) {
  const writes = routes.writes ?? []

  const json = (body: unknown) => ({
    ok: true,
    status: 200,
    json: async () => body,
    text: async () => JSON.stringify(body),
  })

  return vi.fn(async (url: string, init?: RequestInit) => {
    const method = init?.method ?? 'GET'
    if (method !== 'GET') {
      writes.push({
        url,
        method,
        body: init?.body ? JSON.parse(init.body as string) : undefined,
      })
      if (url.startsWith('/api/budgets')) return json(routes.budget ?? budgetView())
      return { ok: true, status: 204, json: async () => ({}), text: async () => '' }
    }
    if (url.startsWith('/api/dashboard')) return json(routes.dashboard ?? dashboard())
    if (url.startsWith('/api/budgets')) return json(routes.budget ?? budgetView())
    if (url.startsWith('/api/transactions')) return json(routes.transactions ?? transactionPage())
    if (url.startsWith('/api/categories')) return json(categories())
    if (url.startsWith('/api/accounts')) return json(accounts())
    throw new Error(`unstubbed request: ${method} ${url}`)
  })
}
