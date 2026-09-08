import { useEffect, useState } from 'react'

import {
  fetchBudget,
  setCategoryBudget,
  setMonthlyBudget,
  type BudgetCategoryLine,
  type BudgetView,
} from '../api'
import {
  formatAmountForInput,
  formatMagnitude,
  formatPercent,
  formatSigned,
  parseAmountToMinor,
} from '../money'

/**
 * Setting the monthly budget and the per-category budgets under it.
 *
 * **You set budgets here; you do not set spending.** Spending is whatever the
 * ledger says, so the "spent" column is read-only and changes only when a
 * transaction is recorded. A typed-in spend figure would immediately contradict
 * the account balances and the transaction list, with nothing to say which was
 * right — so the page links to transaction entry instead of offering a field.
 *
 * The per-category budgets are deliberately allowed not to add up to the
 * monthly total. Under-allocating is normal (the rest is unassigned), and
 * over-allocating is a useful warning rather than an error to block on.
 */
export function Budgets({ onChanged }: { onChanged: () => void }) {
  const [view, setView] = useState<BudgetView | null>(null)
  const [monthlyDraft, setMonthlyDraft] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  function apply(next: BudgetView) {
    setView(next)
    setMonthlyDraft(
      next.allocated ? formatAmountForInput(next.allocatedMinor, next.currency) : '',
    )
  }

  useEffect(() => {
    let live = true
    fetchBudget()
      .then((next) => {
        if (live) apply(next)
      })
      .catch((cause: unknown) => {
        if (live) setError(cause instanceof Error ? cause.message : String(cause))
      })
    return () => {
      live = false
    }
  }, [])

  if (error && !view) {
    return (
      <>
        <header className="page-head">
          <div>
            <h1>Budgets</h1>
          </div>
        </header>
        <section className="card card--error">
          <p>{error}</p>
        </section>
      </>
    )
  }

  if (!view) {
    return (
      <>
        <header className="page-head">
          <div>
            <h1>Budgets</h1>
          </div>
        </header>
        <p className="muted">Loading…</p>
      </>
    )
  }

  // Captured after the null guard above: the save handlers below are closures,
  // and TypeScript cannot carry the narrowing of `view` into them.
  const loaded = view
  const currency = loaded.currency
  const spent = Math.abs(view.spentMinor)
  const consumed = view.allocatedMinor === 0 ? 0 : spent / view.allocatedMinor
  const allocatedToCategories = view.categories.reduce(
    (sum, category) => sum + (category.allocatedMinor ?? 0),
    0,
  )
  const unassigned = view.allocatedMinor - allocatedToCategories

  async function saveMonthly(event: React.FormEvent) {
    event.preventDefault()
    setError(null)
    let amountMinor: number
    try {
      // An empty box clears the budget rather than erroring — it is the natural
      // way to express "no budget this month".
      amountMinor = monthlyDraft.trim() === ''
        ? 0
        : Math.abs(parseAmountToMinor(monthlyDraft, currency))
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause))
      return
    }
    setBusy(true)
    try {
      apply(await setMonthlyBudget(loaded.month, amountMinor))
      onChanged()
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause))
    } finally {
      setBusy(false)
    }
  }

  async function saveCategory(categoryId: number, text: string) {
    setError(null)
    let amountMinor: number
    try {
      amountMinor = text.trim() === '' ? 0 : Math.abs(parseAmountToMinor(text, currency))
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause))
      return
    }
    setBusy(true)
    try {
      apply(await setCategoryBudget(categoryId, loaded.month, amountMinor))
      onChanged()
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause))
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <header className="page-head">
        <div>
          <h1>Budgets</h1>
          <p className="tagline">{monthName(view.month)}</p>
        </div>
      </header>

      {error && (
        <p className="notice notice--error" role="alert">
          {error}
        </p>
      )}

      <section className="card">
        <h2>Monthly budget</h2>
        <form className="inline-form" onSubmit={saveMonthly}>
          <label className="field">
            <span className="field-label">Total for the month ({currency})</span>
            <input
              type="text"
              inputMode="decimal"
              value={monthlyDraft}
              placeholder="2000.00"
              onChange={(event) => setMonthlyDraft(event.target.value)}
            />
          </label>
          <button type="submit" className="btn btn--primary" disabled={busy}>
            Save
          </button>
          <span className="hint">Leave empty to remove the budget.</span>
        </form>

        {view.inherited && view.allocated && (
          <p className="hint">
            Carried forward from an earlier month. Saving it here pins it to{' '}
            {monthName(view.month)}.
          </p>
        )}

        {view.allocated && (
          <dl className="facts">
            <div>
              <dt>Budget</dt>
              <dd>{formatMagnitude(view.allocatedMinor, currency)}</dd>
            </div>
            <div>
              <dt>Spent</dt>
              <dd>{formatMagnitude(view.spentMinor, currency)}</dd>
            </div>
            <div>
              <dt>{view.remainingMinor < 0 ? 'Over by' : 'Left'}</dt>
              <dd className={view.remainingMinor < 0 ? 'over' : undefined}>
                {formatMagnitude(view.remainingMinor, currency)}
              </dd>
            </div>
            <div>
              <dt>Used</dt>
              <dd>{formatPercent(consumed)}</dd>
            </div>
          </dl>
        )}
      </section>

      <section className="card">
        <h2>Budget per category</h2>
        <p className="card-sub">
          Spending is taken from your transactions and cannot be typed here —{' '}
          <a href="#/transactions">record a transaction</a> to change it.
        </p>

        <table className="budget-table">
          <thead>
            <tr>
              <th scope="col">Category</th>
              <th scope="col" className="right">Budget</th>
              <th scope="col" className="right">Spent</th>
              <th scope="col" className="right">Left</th>
            </tr>
          </thead>
          <tbody>
            {view.categories.map((category) => (
              <CategoryRow
                key={category.id}
                category={category}
                currency={currency}
                busy={busy}
                onSave={saveCategory}
              />
            ))}
          </tbody>
        </table>

        {view.allocated && (
          <p className="footnote">
            {unassigned >= 0
              ? `${formatMagnitude(unassigned, currency)} of the monthly budget is not assigned to a category.`
              : `Category budgets exceed the monthly total by ${formatMagnitude(unassigned, currency)}.`}
          </p>
        )}
      </section>
    </>
  )
}

/**
 * One category's row. The budget box keeps its own draft so typing does not
 * fire a request per keystroke; it saves on blur or on Enter.
 */
function CategoryRow({
  category,
  currency,
  busy,
  onSave,
}: {
  category: BudgetCategoryLine
  currency: string
  busy: boolean
  onSave: (categoryId: number, text: string) => Promise<void>
}) {
  const stored =
    category.allocatedMinor === null ? '' : formatAmountForInput(category.allocatedMinor, currency)
  const [draft, setDraft] = useState(stored)

  // Re-sync when the server sends a new value, but never while the user is
  // mid-edit in this box — that would overwrite what they are typing.
  useEffect(() => {
    setDraft(stored)
  }, [stored])

  const spent = Math.abs(category.spentMinor)
  const left = (category.allocatedMinor ?? 0) - spent

  return (
    <tr>
      <th scope="row">{category.name}</th>
      <td className="right">
        <input
          className="cell-input"
          type="text"
          inputMode="decimal"
          value={draft}
          placeholder="—"
          aria-label={`Budget for ${category.name}`}
          disabled={busy}
          onChange={(event) => setDraft(event.target.value)}
          onBlur={() => {
            if (draft !== stored) onSave(category.id, draft)
          }}
          onKeyDown={(event) => {
            if (event.key === 'Enter') {
              event.preventDefault()
              ;(event.target as HTMLInputElement).blur()
            }
          }}
        />
      </td>
      <td className="right amount amount--out">{formatMagnitude(spent, currency)}</td>
      <td className="right">
        {category.allocatedMinor === null ? (
          <span className="muted">—</span>
        ) : (
          <span className={left < 0 ? 'over' : undefined}>{formatSigned(left, currency)}</span>
        )}
      </td>
    </tr>
  )
}

function monthName(month: string): string {
  const [year, monthNumber] = month.split('-').map(Number)
  return new Date(year, monthNumber - 1, 1).toLocaleDateString(undefined, {
    month: 'long',
    year: 'numeric',
  })
}
