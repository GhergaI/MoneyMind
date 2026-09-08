import { useEffect, useMemo, useState } from 'react'

import {
  createTransaction,
  createTransfer,
  deleteTransaction,
  fetchAccounts,
  fetchCategories,
  fetchTransactions,
  updateTransaction,
  type AccountOption,
  type CategoryOption,
  type EntryLine,
  type TransactionPage,
} from '../api'
import {
  AmountFormatError,
  formatAmountForInput,
  formatDay,
  formatSigned,
  parseAmountToMinor,
  todayIso,
} from '../money'

/**
 * Phase 1: recording what you spent.
 *
 * **The form collects a positive amount plus a direction, and does the sign
 * arithmetic before sending.** Amounts are stored signed from the account's
 * perspective, but asking a person to type `-42.10` for a normal purchase is a
 * needless trap. The three directions map onto the ledger like this:
 *
 * - *Money out* — an EXPENSE with a negative amount. The ordinary case.
 * - *Money in* — an INCOME with a positive amount.
 * - *Refund* — an EXPENSE with a **positive** amount. Not income: it reduces
 *   what that category cost rather than adding to what you earned, which is
 *   why it is a direction of its own instead of a minus sign.
 *
 * Moving money between your own accounts is a separate form, because a transfer
 * is not a transaction: it is two opposing legs under one parent row, and the
 * schema will not accept half of one.
 */

type Direction = 'OUT' | 'IN' | 'REFUND'

const DIRECTIONS: { id: Direction; label: string; hint: string }[] = [
  { id: 'OUT', label: 'Money out', hint: 'A purchase or bill' },
  { id: 'IN', label: 'Money in', hint: 'Salary or other income' },
  { id: 'REFUND', label: 'Refund', hint: 'Money back — reduces the category' },
]

/** Direction to the stored (kind, sign) pair. The one place the sign is decided. */
function toKindAndSign(direction: Direction): { kind: 'INCOME' | 'EXPENSE'; sign: 1 | -1 } {
  switch (direction) {
    case 'OUT':
      return { kind: 'EXPENSE', sign: -1 }
    case 'IN':
      return { kind: 'INCOME', sign: 1 }
    case 'REFUND':
      return { kind: 'EXPENSE', sign: 1 }
  }
}

function directionOf(entry: EntryLine): Direction {
  if (entry.kind === 'INCOME') return 'IN'
  return entry.amountMinor < 0 ? 'OUT' : 'REFUND'
}

type Draft = {
  id: number | null
  direction: Direction
  accountId: string
  categoryId: string
  amount: string
  bookedOn: string
  description: string
}

function emptyDraft(accountId: number | undefined): Draft {
  return {
    id: null,
    direction: 'OUT',
    accountId: accountId === undefined ? '' : String(accountId),
    categoryId: '',
    amount: '',
    bookedOn: todayIso(),
    description: '',
  }
}

export function Transactions({ onChanged }: { onChanged: () => void }) {
  const [page, setPage] = useState<TransactionPage | null>(null)
  const [categories, setCategories] = useState<CategoryOption[]>([])
  const [accounts, setAccounts] = useState<AccountOption[]>([])
  const [draft, setDraft] = useState<Draft>(emptyDraft(undefined))
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [showTransfer, setShowTransfer] = useState(false)

  async function reload() {
    const [entries, categoryList, accountList] = await Promise.all([
      fetchTransactions(),
      fetchCategories(),
      fetchAccounts(),
    ])
    setPage(entries)
    setCategories(categoryList)
    setAccounts(accountList)
    return accountList
  }

  useEffect(() => {
    let live = true
    reload()
      .then((accountList) => {
        // Default the account only once, and only if the user has not begun.
        if (live) setDraft((current) => (current.accountId ? current : emptyDraft(accountList[0]?.id)))
      })
      .catch((cause: unknown) => {
        if (live) setError(cause instanceof Error ? cause.message : String(cause))
      })
    return () => {
      live = false
    }
  }, [])

  const { kind } = toKindAndSign(draft.direction)

  /**
   * Only categories matching the direction are offered. Making the invalid
   * pair unselectable beats rejecting it after everything else is typed.
   */
  const selectableCategories = useMemo(
    () => categories.filter((category) => category.kind === kind),
    [categories, kind],
  )

  // A category valid for "Money out" is not valid for "Money in", so switching
  // direction has to drop a selection that no longer applies.
  useEffect(() => {
    setDraft((current) => {
      if (!current.categoryId) return current
      const stillValid = categories.some(
        (category) => String(category.id) === current.categoryId && category.kind === kind,
      )
      return stillValid ? current : { ...current, categoryId: '' }
    })
  }, [kind, categories])

  const currency = page?.currency ?? 'EUR'

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    setError(null)

    let amountMinor: number
    try {
      const magnitude = Math.abs(parseAmountToMinor(draft.amount, currency))
      if (magnitude === 0) throw new AmountFormatError('Enter an amount greater than zero')
      amountMinor = magnitude * toKindAndSign(draft.direction).sign
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause))
      return
    }

    if (!draft.categoryId) {
      setError('Choose a category')
      return
    }

    const request = {
      accountId: Number(draft.accountId),
      categoryId: Number(draft.categoryId),
      kind: toKindAndSign(draft.direction).kind,
      amountMinor,
      bookedOn: draft.bookedOn,
      description: draft.description,
    }

    setBusy(true)
    try {
      if (draft.id === null) {
        await createTransaction(request)
      } else {
        await updateTransaction(draft.id, request)
      }
      setDraft(emptyDraft(Number(draft.accountId)))
      await reload()
      onChanged()
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause))
    } finally {
      setBusy(false)
    }
  }

  function edit(entry: EntryLine) {
    const account = accounts.find((candidate) => candidate.name === entry.accountName)
    const category = categories.find((candidate) => candidate.name === entry.categoryName)
    setError(null)
    setDraft({
      id: entry.id,
      direction: directionOf(entry),
      accountId: account ? String(account.id) : draft.accountId,
      categoryId: category ? String(category.id) : '',
      // Edited as a magnitude, matching how it was entered.
      amount: formatAmountForInput(Math.abs(entry.amountMinor), currency),
      bookedOn: entry.bookedOn,
      description: entry.description ?? '',
    })
  }

  async function remove(entry: EntryLine) {
    setBusy(true)
    setError(null)
    try {
      await deleteTransaction(entry.id)
      if (draft.id === entry.id) setDraft(emptyDraft(Number(draft.accountId)))
      await reload()
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
          <h1>Transactions</h1>
          <p className="tagline">{page ? monthName(page.month) : 'Loading…'}</p>
        </div>
        <button type="button" className="btn btn--ghost" onClick={() => setShowTransfer((on) => !on)}>
          {showTransfer ? 'Hide transfer' : 'Move money between accounts'}
        </button>
      </header>

      {error && (
        <p className="notice notice--error" role="alert">
          {error}
        </p>
      )}

      <section className="card">
        <h2>{draft.id === null ? 'Add a transaction' : 'Edit transaction'}</h2>

        <form className="entry-form" onSubmit={submit}>
          <div className="field field--directions">
            <span className="field-label">Direction</span>
            <div className="segmented" role="radiogroup" aria-label="Direction">
              {DIRECTIONS.map((option) => (
                <button
                  key={option.id}
                  type="button"
                  role="radio"
                  aria-checked={draft.direction === option.id}
                  title={option.hint}
                  className={
                    draft.direction === option.id ? 'segment segment--on' : 'segment'
                  }
                  onClick={() => setDraft({ ...draft, direction: option.id })}
                >
                  {option.label}
                </button>
              ))}
            </div>
          </div>

          <label className="field">
            <span className="field-label">Amount ({currency})</span>
            <input
              // Text, not number: a number input's locale handling of the
              // decimal separator differs by browser, and parsing is exact here.
              type="text"
              inputMode="decimal"
              value={draft.amount}
              placeholder="12.34"
              onChange={(event) => setDraft({ ...draft, amount: event.target.value })}
              required
            />
          </label>

          <label className="field">
            <span className="field-label">Category</span>
            <select
              value={draft.categoryId}
              onChange={(event) => setDraft({ ...draft, categoryId: event.target.value })}
              required
            >
              <option value="">Choose…</option>
              {selectableCategories.map((category) => (
                <option key={category.id} value={category.id}>
                  {category.name}
                </option>
              ))}
            </select>
          </label>

          <label className="field">
            <span className="field-label">Date</span>
            <input
              type="date"
              value={draft.bookedOn}
              onChange={(event) => setDraft({ ...draft, bookedOn: event.target.value })}
              required
            />
          </label>

          <label className="field field--wide">
            <span className="field-label">Description</span>
            <input
              type="text"
              value={draft.description}
              placeholder="Supermarket"
              onChange={(event) => setDraft({ ...draft, description: event.target.value })}
            />
          </label>

          {accounts.length > 1 && (
            <label className="field">
              <span className="field-label">Account</span>
              <select
                value={draft.accountId}
                onChange={(event) => setDraft({ ...draft, accountId: event.target.value })}
              >
                {accounts.map((account) => (
                  <option key={account.id} value={account.id}>
                    {account.name}
                  </option>
                ))}
              </select>
            </label>
          )}

          <div className="field field--actions">
            <button type="submit" className="btn btn--primary" disabled={busy}>
              {draft.id === null ? 'Add' : 'Save'}
            </button>
            {draft.id !== null && (
              <button
                type="button"
                className="btn btn--ghost"
                onClick={() => {
                  setError(null)
                  setDraft(emptyDraft(Number(draft.accountId)))
                }}
              >
                Cancel
              </button>
            )}
          </div>
        </form>
      </section>

      {showTransfer && (
        <TransferForm
          accounts={accounts}
          currency={currency}
          onDone={async () => {
            await reload()
            onChanged()
          }}
        />
      )}

      <section className="card">
        <h2>This month</h2>
        {!page || page.entries.length === 0 ? (
          <p className="empty">Nothing recorded this month yet.</p>
        ) : (
          <table className="ledger">
            <thead>
              <tr>
                <th scope="col">Date</th>
                <th scope="col">Description</th>
                <th scope="col">Category</th>
                <th scope="col">Account</th>
                <th scope="col" className="right">Amount</th>
                <th scope="col" className="right">Actions</th>
              </tr>
            </thead>
            <tbody>
              {page.entries.map((entry) => (
                <tr key={entry.id}>
                  <td className="nowrap muted">{formatDay(entry.bookedOn)}</td>
                  <td>
                    {entry.description ?? '—'}
                    {entry.kind === 'TRANSFER' && (
                      <span className="badge badge--transfer">Transfer</span>
                    )}
                    {entry.kind === 'EXPENSE' && entry.amountMinor > 0 && (
                      <span className="badge">Refund</span>
                    )}
                  </td>
                  <td className="muted">{entry.categoryName ?? '—'}</td>
                  <td className="muted">{entry.accountName}</td>
                  <td
                    className={`right amount ${
                      entry.kind === 'TRANSFER'
                        ? 'amount--neutral'
                        : entry.amountMinor < 0
                          ? 'amount--out'
                          : 'amount--in'
                    }`}
                  >
                    {formatSigned(entry.amountMinor, currency)}
                  </td>
                  <td className="right nowrap">
                    {/* A transfer leg cannot be edited alone: the two legs must
                        stay equal and opposite, so only deleting the pair is
                        offered. */}
                    {entry.kind !== 'TRANSFER' && (
                      <button type="button" className="btn btn--tiny" onClick={() => edit(entry)}>
                        Edit
                      </button>
                    )}
                    <button
                      type="button"
                      className="btn btn--tiny btn--danger"
                      onClick={() => remove(entry)}
                      disabled={busy}
                    >
                      Delete
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </>
  )
}

/**
 * Moving money between your own accounts.
 *
 * Kept apart from the transaction form because it is a different thing: the
 * amount is a magnitude and the direction comes from which accounts are chosen,
 * there is no category by definition, and one submission writes three rows.
 */
function TransferForm({
  accounts,
  currency,
  onDone,
}: {
  accounts: AccountOption[]
  currency: string
  onDone: () => Promise<void>
}) {
  const [fromId, setFromId] = useState('')
  const [toId, setToId] = useState('')
  const [amount, setAmount] = useState('')
  const [bookedOn, setBookedOn] = useState(todayIso())
  const [note, setNote] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    setError(null)

    let amountMinor: number
    try {
      amountMinor = Math.abs(parseAmountToMinor(amount, currency))
      if (amountMinor === 0) throw new AmountFormatError('Enter an amount greater than zero')
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause))
      return
    }
    if (!fromId || !toId) {
      setError('Choose both accounts')
      return
    }

    setBusy(true)
    try {
      await createTransfer({
        fromAccountId: Number(fromId),
        toAccountId: Number(toId),
        amountMinor,
        bookedOn,
        note,
      })
      setAmount('')
      setNote('')
      await onDone()
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause))
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className="card">
      <h2>Move money between accounts</h2>
      <p className="card-sub">
        A transfer moves both balances but is neither income nor spending, so it
        never touches a budget.
      </p>

      {error && (
        <p className="notice notice--error" role="alert">
          {error}
        </p>
      )}

      <form className="entry-form" onSubmit={submit}>
        <label className="field">
          <span className="field-label">From</span>
          <select value={fromId} onChange={(event) => setFromId(event.target.value)} required>
            <option value="">Choose…</option>
            {accounts.map((account) => (
              <option key={account.id} value={account.id}>
                {account.name}
              </option>
            ))}
          </select>
        </label>

        <label className="field">
          <span className="field-label">To</span>
          <select value={toId} onChange={(event) => setToId(event.target.value)} required>
            <option value="">Choose…</option>
            {accounts
              // Excluding the chosen source is clearer than validating it after.
              .filter((account) => String(account.id) !== fromId)
              .map((account) => (
                <option key={account.id} value={account.id}>
                  {account.name}
                </option>
              ))}
          </select>
        </label>

        <label className="field">
          <span className="field-label">Amount ({currency})</span>
          <input
            type="text"
            inputMode="decimal"
            value={amount}
            placeholder="400.00"
            onChange={(event) => setAmount(event.target.value)}
            required
          />
        </label>

        <label className="field">
          <span className="field-label">Date</span>
          <input
            type="date"
            value={bookedOn}
            onChange={(event) => setBookedOn(event.target.value)}
            required
          />
        </label>

        <label className="field field--wide">
          <span className="field-label">Note</span>
          <input
            type="text"
            value={note}
            placeholder="Monthly saving"
            onChange={(event) => setNote(event.target.value)}
          />
        </label>

        <div className="field field--actions">
          <button type="submit" className="btn btn--primary" disabled={busy}>
            Record transfer
          </button>
        </div>
      </form>
    </section>
  )
}

function monthName(month: string): string {
  const [year, monthNumber] = month.split('-').map(Number)
  return new Date(year, monthNumber - 1, 1).toLocaleDateString(undefined, {
    month: 'long',
    year: 'numeric',
  })
}
