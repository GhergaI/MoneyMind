/**
 * The shape of `GET /api/dashboard`, mirroring `DashboardResponse` on the
 * server. Every amount is an integer count of minor units in `currency`.
 */

export type Totals = {
  totalBalanceMinor: number
  netWorthMinor: number
  savingsMinor: number
  monthIncomeMinor: number
  /** Signed, so normally negative. */
  monthSpendMinor: number
}

export type AccountLine = {
  id: number
  name: string
  type: 'CURRENT' | 'SAVINGS' | 'CASH' | 'CREDIT_CARD' | 'INVESTMENT'
  balanceMinor: number
  asset: boolean
}

export type CategorySlice = {
  categoryId: number
  name: string
  systemKey: string | null
  /** Signed, so negative. */
  spentMinor: number
}

export type TrendPoint = {
  /** `2026-09` */
  month: string
  label: string
  spentMinor: number
}

export type BudgetLine = {
  id: number
  categoryName: string
  allocatedMinor: number
  /** Signed, so negative. */
  spentMinor: number
  startsOn: string
  endsOn: string
}

export type EntryLine = {
  id: number
  bookedOn: string
  description: string | null
  kind: 'INCOME' | 'EXPENSE' | 'TRANSFER'
  accountName: string
  categoryName: string | null
  amountMinor: number
}

export type GoalLine = {
  id: number
  name: string
  targetMinor: number
  savedMinor: number
  targetDate: string | null
}

export type InsightTone = 'POSITIVE' | 'NEUTRAL' | 'ATTENTION'

export type InsightCard = {
  code: string
  tone: InsightTone
  message: string
  /**
   * The computed values behind the message. Numbers shown in the UI are read
   * from here — never parsed out of `message`, which an LLM is allowed to
   * reword.
   */
  facts: Record<string, string>
}

/**
 * The overall budget for the month.
 *
 * `allocated` distinguishes "no budget set" from "a budget of zero" — the donut
 * is only meaningful in the first case, and defaulting the second to a prompt
 * would hide a deliberate choice.
 */
export type MonthlyBudget = {
  allocatedMinor: number
  allocated: boolean
  /** Budget minus spending. Negative means overspent, and is not clamped. */
  remainingMinor: number
}

export type Dashboard = {
  currency: string
  month: string
  monthLabel: string
  today: string
  /** Currencies held in other accounts, excluded from these totals. */
  otherCurrencies: string[]
  totals: Totals
  monthlyBudget: MonthlyBudget
  accounts: AccountLine[]
  spendingByCategory: CategorySlice[]
  spendingTrend: TrendPoint[]
  budgets: BudgetLine[]
  recentTransactions: EntryLine[]
  savingsGoals: GoalLine[]
  insights: InsightCard[]
}

export async function fetchDashboard(): Promise<Dashboard> {
  const response = await fetch('/api/dashboard')
  if (!response.ok) throw new Error(`HTTP ${response.status}`)
  return (await response.json()) as Dashboard
}

// --- reference data -------------------------------------------------------

export type CategoryOption = {
  id: number
  name: string
  systemKey: string | null
  kind: 'INCOME' | 'EXPENSE'
}

export type AccountOption = {
  id: number
  name: string
  type: AccountLine['type']
  currency: string
  balanceMinor: number
}

// --- budgets --------------------------------------------------------------

export type BudgetCategoryLine = {
  id: number
  name: string
  systemKey: string | null
  /** null when the category has no budget, which is not the same as zero. */
  allocatedMinor: number | null
  /** Signed, so negative. */
  spentMinor: number
}

export type BudgetView = {
  currency: string
  month: string
  allocatedMinor: number
  allocated: boolean
  /** The amount was carried forward from an earlier month, not set for this one. */
  inherited: boolean
  spentMinor: number
  remainingMinor: number
  categories: BudgetCategoryLine[]
}

// --- transactions ---------------------------------------------------------

export type TransactionPage = {
  currency: string
  month: string
  entries: EntryLine[]
}

/** Signed minor units, exactly as stored: a positive expense is a refund. */
export type TransactionRequest = {
  accountId: number
  categoryId: number
  kind: 'INCOME' | 'EXPENSE'
  amountMinor: number
  bookedOn: string
  description?: string
  notes?: string
}

export type TransferRequest = {
  fromAccountId: number
  toAccountId: number
  /** A positive magnitude; direction comes from the two accounts. */
  amountMinor: number
  bookedOn: string
  note?: string
}

/**
 * Reads the server's own explanation out of a failed response.
 *
 * The API answers a rejected write with `{ "message": "..." }` describing what
 * rule was broken, in words the user can act on. Falling back to the status
 * code loses exactly the part that was worth showing.
 */
async function failure(response: Response): Promise<Error> {
  try {
    const body = (await response.json()) as { message?: string }
    if (body.message) return new Error(body.message)
  } catch {
    // Not JSON — fall through to the status code.
  }
  return new Error(`HTTP ${response.status}`)
}

async function send<T>(url: string, method: string, body?: unknown): Promise<T> {
  const response = await fetch(url, {
    method,
    headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  if (!response.ok) throw await failure(response)
  if (response.status === 204) return undefined as T
  const text = await response.text()
  return (text ? JSON.parse(text) : undefined) as T
}

async function read<T>(url: string): Promise<T> {
  const response = await fetch(url)
  if (!response.ok) throw await failure(response)
  return (await response.json()) as T
}

export const fetchCategories = () => read<CategoryOption[]>('/api/categories')
export const fetchAccounts = () => read<AccountOption[]>('/api/accounts')

export const fetchBudget = (month?: string) =>
  read<BudgetView>(month ? `/api/budgets?month=${month}` : '/api/budgets')

export const setMonthlyBudget = (month: string, amountMinor: number) =>
  send<BudgetView>('/api/budgets/monthly', 'PUT', { month, amountMinor })

export const setCategoryBudget = (categoryId: number, month: string, amountMinor: number) =>
  send<BudgetView>(`/api/budgets/category/${categoryId}`, 'PUT', { month, amountMinor })

export const fetchTransactions = (month?: string) =>
  read<TransactionPage>(month ? `/api/transactions?month=${month}` : '/api/transactions')

export const createTransaction = (request: TransactionRequest) =>
  send<{ id: number }>('/api/transactions', 'POST', request)

export const updateTransaction = (id: number, request: TransactionRequest) =>
  send<void>(`/api/transactions/${id}`, 'PUT', request)

export const deleteTransaction = (id: number) =>
  send<void>(`/api/transactions/${id}`, 'DELETE')

export const createTransfer = (request: TransferRequest) =>
  send<{ id: number }>('/api/transactions/transfer', 'POST', request)
