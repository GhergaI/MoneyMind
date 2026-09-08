import type { EntryLine } from '../api'
import { formatDay, formatSigned } from '../money'

/**
 * The latest ledger rows.
 *
 * **Transfers are shown but never coloured as money in or out.** A transfer
 * between your own accounts moved a balance without earning or spending
 * anything, so it gets a neutral tone and an explicit badge. Tinting it green
 * or red would make the list contradict the totals above it, which filter
 * transfers out.
 *
 * Amounts keep their stored sign here — this is a ledger, and a column of
 * signed figures is exactly how one reads. `tabular-nums` aligns the digits so
 * the column can be scanned.
 */

const KIND_LABEL: Record<EntryLine['kind'], string> = {
  INCOME: 'Income',
  EXPENSE: 'Expense',
  TRANSFER: 'Transfer',
}

function toneFor(kind: EntryLine['kind'], amountMinor: number): string {
  if (kind === 'TRANSFER') return 'amount amount--neutral'
  // A refund is a positive EXPENSE. It reads as money coming back, so it is
  // shown positive — but it is not income, and the badge still says Expense.
  return amountMinor < 0 ? 'amount amount--out' : 'amount amount--in'
}

export function RecentTransactions({
  entries,
  currency,
}: {
  entries: EntryLine[]
  currency: string
}) {
  return (
    <section className="card">
      <h2>Recent transactions</h2>
      {entries.length === 0 ? (
        <p className="empty">Nothing recorded yet.</p>
      ) : (
        <table className="ledger">
          <thead>
            <tr>
              <th scope="col">Date</th>
              <th scope="col">Description</th>
              <th scope="col">Category</th>
              <th scope="col">Account</th>
              <th scope="col" className="right">
                Amount
              </th>
            </tr>
          </thead>
          <tbody>
            {entries.map((entry) => (
              <tr key={entry.id}>
                <td className="nowrap muted">{formatDay(entry.bookedOn)}</td>
                <td>
                  {entry.description ?? '—'}
                  {entry.kind === 'TRANSFER' && (
                    <span className="badge badge--transfer">{KIND_LABEL.TRANSFER}</span>
                  )}
                </td>
                <td className="muted">{entry.categoryName ?? '—'}</td>
                <td className="muted">{entry.accountName}</td>
                <td className={`right ${toneFor(entry.kind, entry.amountMinor)}`}>
                  {/* One currency for the whole snapshot: the server reports a
                      single currency and names any others separately, so no row
                      here can be in a different one. */}
                  {formatSigned(entry.amountMinor, currency)}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  )
}
