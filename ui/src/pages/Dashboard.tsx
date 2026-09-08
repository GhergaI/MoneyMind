import type { Dashboard as DashboardData } from '../api'
import { BudgetDonut } from '../dashboard/BudgetDonut'
import { BudgetProgress } from '../dashboard/BudgetProgress'
import { MoneyInsights } from '../dashboard/MoneyInsights'
import { RecentTransactions } from '../dashboard/RecentTransactions'
import { SavingsPanel } from '../dashboard/SavingsPanel'
import { SpendingByCategory } from '../dashboard/SpendingByCategory'
import { SpendingTrend } from '../dashboard/SpendingTrend'
import { StatTiles } from '../dashboard/StatTiles'

/**
 * The main screen.
 *
 * Reading order is deliberate: where you stand (the tiles), where the money
 * went (the two charts), whether that was the plan (budgets and goals), what
 * actually happened (recent transactions), and only then the interpretation
 * (insights). Facts before commentary — the insights are the least authoritative
 * thing on the page and sit last rather than leading.
 */
export function Dashboard({ data }: { data: DashboardData }) {
  return (
    <>
      <header className="page-head">
        <div>
          <h1>Dashboard</h1>
          <p className="tagline">{data.monthLabel}</p>
        </div>
      </header>

      {data.otherCurrencies.length > 0 && (
        // Never sum across currencies — say so rather than present a partial
        // total as if it were everything.
        <p className="notice">
          Showing {data.currency} only. You also hold {data.otherCurrencies.join(', ')}, which is
          not included in these totals.
        </p>
      )}

      <StatTiles dashboard={data} />

      <div className="grid grid--two">
        <BudgetDonut
          categories={data.spendingByCategory}
          budget={data.monthlyBudget}
          currency={data.currency}
          monthLabel={data.monthLabel}
        />
        <SpendingByCategory categories={data.spendingByCategory} currency={data.currency} />
      </div>

      <div className="grid grid--two">
        <SpendingTrend
          trend={data.spendingTrend}
          currency={data.currency}
          currentMonth={data.month}
        />
        <BudgetProgress budgets={data.budgets} currency={data.currency} />
      </div>

      <div className="grid grid--two">
        <SavingsPanel goals={data.savingsGoals} currency={data.currency} />
        <MoneyInsights insights={data.insights} />
      </div>

      <RecentTransactions entries={data.recentTransactions} currency={data.currency} />
    </>
  )
}
