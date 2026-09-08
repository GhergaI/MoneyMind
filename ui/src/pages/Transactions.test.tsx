import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

import { Transactions } from './Transactions'
import { stubApi, type StubRoutes } from '../test-fixtures'

/**
 * The sign arithmetic is the whole risk in this form. A refund stored as income,
 * or a purchase stored positive, is silent and corrupts every total downstream.
 */
describe('Transactions entry', () => {
  function setup() {
    const writes: NonNullable<StubRoutes['writes']> = []
    vi.stubGlobal('fetch', stubApi({ writes }))
    render(<Transactions onChanged={() => {}} />)
    return { writes, user: userEvent.setup() }
  }

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('stores money out as a negative expense', async () => {
    const { writes, user } = setup()

    await user.type(await screen.findByLabelText(/Amount/), '42.10')
    await user.selectOptions(screen.getByLabelText('Category'), 'Food')
    await user.click(screen.getByRole('button', { name: 'Add' }))

    await waitFor(() => expect(writes).toHaveLength(1))
    expect(writes[0].method).toBe('POST')
    expect(writes[0].body).toMatchObject({ kind: 'EXPENSE', amountMinor: -4210, categoryId: 20 })
  })

  it('stores a refund as a POSITIVE expense, never as income', async () => {
    const { writes, user } = setup()

    await user.click(await screen.findByRole('radio', { name: 'Refund' }))
    await user.type(screen.getByLabelText(/Amount/), '38')
    await user.selectOptions(screen.getByLabelText('Category'), 'Household')
    await user.click(screen.getByRole('button', { name: 'Add' }))

    await waitFor(() => expect(writes).toHaveLength(1))
    // The distinction the ledger depends on: kind stays EXPENSE, sign flips.
    expect(writes[0].body).toMatchObject({ kind: 'EXPENSE', amountMinor: 3800 })
  })

  it('stores money in as a positive income', async () => {
    const { writes, user } = setup()

    await user.click(await screen.findByRole('radio', { name: 'Money in' }))
    await user.type(screen.getByLabelText(/Amount/), '3200')
    await user.selectOptions(screen.getByLabelText('Category'), 'Salary')
    await user.click(screen.getByRole('button', { name: 'Add' }))

    await waitFor(() => expect(writes).toHaveLength(1))
    expect(writes[0].body).toMatchObject({ kind: 'INCOME', amountMinor: 320000 })
  })

  it('offers only categories matching the direction', async () => {
    const { user } = setup()

    const picker = await screen.findByLabelText('Category')
    // Money out: expense categories only, so Salary must not be selectable.
    expect(within(picker).queryByRole('option', { name: 'Salary' })).toBeNull()
    expect(within(picker).getByRole('option', { name: 'Food' })).toBeInTheDocument()

    await user.click(screen.getByRole('radio', { name: 'Money in' }))
    expect(within(picker).getByRole('option', { name: 'Salary' })).toBeInTheDocument()
    expect(within(picker).queryByRole('option', { name: 'Food' })).toBeNull()
  })

  it('rejects more precision than the currency has, rather than rounding it away', async () => {
    const { writes, user } = setup()

    await user.type(await screen.findByLabelText(/Amount/), '10.123')
    await user.selectOptions(screen.getByLabelText('Category'), 'Food')
    await user.click(screen.getByRole('button', { name: 'Add' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'EUR amounts have at most 2 decimal places',
    )
    expect(writes).toHaveLength(0)
  })

  it('does not offer to edit a transfer leg on its own', async () => {
    setup()

    const transferRow = (await screen.findByText('Monthly saving')).closest('tr') as HTMLElement
    // Deleting the pair is fine; editing one side would unbalance the transfer.
    expect(within(transferRow).queryByRole('button', { name: 'Edit' })).toBeNull()
    expect(within(transferRow).getByRole('button', { name: 'Delete' })).toBeInTheDocument()
  })

  it('labels a positive expense in the list as a refund', async () => {
    setup()

    const refundRow = (await screen.findByText('Returned item')).closest('tr') as HTMLElement
    expect(within(refundRow).getByText('Refund')).toBeInTheDocument()
  })
})
