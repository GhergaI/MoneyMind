import { render, screen, within } from '@testing-library/react'

import { BudgetDonut } from './BudgetDonut'
import { dashboard } from '../test-fixtures'

const categories = dashboard().spendingByCategory

describe('BudgetDonut', () => {
  it('shows the unspent remainder as its own slice', () => {
    render(
      <BudgetDonut
        categories={categories}
        // 2000.00 budgeted, 1277.60 spent.
        budget={{ allocatedMinor: 200000, allocated: true, remainingMinor: 72240 }}
        currency="EUR"
        monthLabel="September 2026"
      />,
    )

    expect(screen.getByText('Left to spend')).toBeInTheDocument()
    expect(screen.getByText('€722.40')).toBeInTheDocument()
    expect(screen.getByText('64%')).toBeInTheDocument()
  })

  it('names every slice with its amount, so colour is never the only channel', () => {
    render(
      <BudgetDonut
        categories={categories}
        budget={{ allocatedMinor: 200000, allocated: true, remainingMinor: 72240 }}
        currency="EUR"
        monthLabel="September 2026"
      />,
    )

    for (const name of ['Food', 'Household', 'Health']) {
      expect(screen.getByText(name)).toBeInTheDocument()
    }
    expect(screen.getByText('€800.00')).toBeInTheDocument()
    expect(screen.getByText('€300.00')).toBeInTheDocument()
  })

  it('reports overspending instead of drawing a more-than-full ring', () => {
    render(
      <BudgetDonut
        categories={categories}
        // Only 1000.00 budgeted against 1277.60 spent.
        budget={{ allocatedMinor: 100000, allocated: true, remainingMinor: -27760 }}
        currency="EUR"
        monthLabel="September 2026"
      />,
    )

    expect(screen.getByText('Over budget by €277.60')).toBeInTheDocument()
    // With no budget left there is no remainder slice to show.
    expect(screen.queryByText('Left to spend')).toBeNull()
  })

  it('prompts for a budget rather than drawing an empty ring', () => {
    render(
      <BudgetDonut
        categories={categories}
        budget={{ allocatedMinor: 0, allocated: false, remainingMinor: 0 }}
        currency="EUR"
        monthLabel="September 2026"
      />,
    )

    expect(screen.getByText(/No budget set for September 2026/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Budgets' })).toHaveAttribute('href', '#/budgets')
  })

  it('binds a hue to the category rather than to its rank', () => {
    // Food leads here; in the reordered set Household does. Food must keep its
    // colour either way, or every month-over-month comparison is a repaint.
    const { container, unmount } = render(
      <BudgetDonut
        categories={categories}
        budget={{ allocatedMinor: 200000, allocated: true, remainingMinor: 72240 }}
        currency="EUR"
        monthLabel="September 2026"
      />,
    )
    const foodFirst = within(container).getByText('Food').previousElementSibling
    const foodHue = (foodFirst as HTMLElement).style.background
    unmount()

    const reordered = [
      { ...categories[1], spentMinor: -900000 },
      ...categories.filter((_, index) => index !== 1),
    ]
    const second = render(
      <BudgetDonut
        categories={reordered}
        budget={{ allocatedMinor: 2000000, allocated: true, remainingMinor: 100000 }}
        currency="EUR"
        monthLabel="September 2026"
      />,
    )
    const foodLater = within(second.container).getByText('Food').previousElementSibling

    expect((foodLater as HTMLElement).style.background).toBe(foodHue)
  })
})
