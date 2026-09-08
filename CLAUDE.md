# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

MoneyMind is a personal finance app: track income and expenses, manage budgets,
monitor savings, understand spending habits, track recurring payments and
subscriptions, and get saving recommendations. The product goal is to be
**simple, visual and motivating rather than overwhelming**.

Fixed decisions — do not reintroduce what these rule out:

- **Desktop web app only.** Large screen, rich charts, fast entry. Phone is not a goal.
- **Single user, runs locally.** No authentication, no user table, no multi-tenancy, no cloud.
- **Manual entry now; CSV import later.** The schema already reserves the import columns.
- **Rules first, LLM optional.** Deterministic rules compute the facts; an LLM only rewords them.

Phase 0 (foundation) is complete: build, schema, `Money`, health endpoint, UI shell.

**Phase 1 is complete.** Transactions can be added, edited and deleted through
the UI, transfers are recorded as a proper pair of legs, and the monthly budget
and per-category budgets are editable. The dashboard reads all of it live.

What exists now:

- `GET /api/dashboard` — the whole main screen in one read.
- `/api/transactions` — list, create, edit, delete, plus `POST .../transfer`.
- `/api/budgets` — read a month, set the monthly total, set a category budget.
- `/api/categories`, `/api/accounts` — the pickers an entry form needs.
- `/api/goals` — list, create, edit, archive a savings goal. Progress is derived
  and there is no way to write a saved figure.
- Screens: Dashboard, Transactions, Budgets. Goals, Insights and Settings are
  still honest placeholders.

Later phases: editing goals and accounts, subscriptions and recurring
detection, the fuller insight feed, CSV import, optional LLM phrasing.

## Architecture

Single Maven module. Spring Boot 3.5.6 REST API on Java 21, SQLite via Flyway
and JPA, with a React 19 + TypeScript SPA in `ui/` built into the jar.

Packages under `dev.igherga.moneymind` are **feature-first**, not layer-first —
a feature's entity, repository, service and controller live together:

```
common       Money and shared types — no Spring, no JPA
transaction  TransactionService — the only write path into the ledger
budget       BudgetRepository, BudgetService, BudgetPeriodCalculator
savings      GoalRepository, GoalService, FundingMode — goals; progress is read
             through reporting, never stored
account/ category/ recurring/   not yet created
reporting    native SQL projections to DTOs, never entities
             ReportingQueries (all the SQL), DashboardService, DashboardSnapshot
insight      Insight, InsightRule, AnalysisContext, InsightService
insight/rules     the rules themselves — plain objects, no annotations
insight/narrate   InsightPhraser: Passthrough (default) and Claude
web          controllers and DTOs; entities are never exposed
config       Spring configuration, first-run bootstrap, demo-data seeder
```

Controllers live in `web`, not beside their service — that is what the existing
code does and the package list above reflects it. Everything else about a
feature (service, repository, commands) stays together in the feature package.

The UI mirrors this: `ui/src/dashboard/` holds one component per panel,
`ui/src/nav/` the sidebar and router, `ui/src/money.ts` all minor-unit
formatting and parsing, `ui/src/pages/` the routed screens, and
`ui/src/test-fixtures.ts` the shared payload fixtures plus a URL-routing `fetch`
stub (pages fetch their own data now, so a stub that answers every URL the same
way feeds a page fields that are simply absent).

Calculation classes (`Money`, `BudgetPeriodCalculator`, `RecurringMatcher`,
every `InsightRule`) carry no Spring or JPA annotations, so they unit-test
without a context. Keep them that way. `@Transactional` belongs on service
methods only.

## Commands

**JDK note — narrower than it looks.** The bare `java` on PATH is JDK
1.8.0_292, but **Maven is unaffected**: `mvn` and `./mvnw` both resolve a JDK via
`/usr/libexec/java_home`, whose default here is the Microsoft JDK 21. Builds and
tests therefore need no setup at all.

It bites in exactly one place — running the jar directly. `java -jar
target/moneymind.jar` dies with `UnsupportedClassVersionError` (class file 61.0
vs 52.0). Use `./run.sh`, which pins the JDK itself, or
`export JAVA_HOME=$(/usr/libexec/java_home -v 21)` first. A `maven-enforcer`
rule requiring `[21,)` guards against `JAVA_HOME` being pointed at an older JDK.

| Task | Command |
|---|---|
| Run everything | `./run.sh` (packages, then serves API + UI on `:8080`) |
| Backend only | `./run.sh dev` or `./mvnw -DskipFrontend spring-boot:run` |
| Frontend dev server | `cd ui && npm run dev` (`:5173`, proxies `/api` to `:8080`) |
| Backend tests | `./mvnw -DskipFrontend test` |
| One test class | `./mvnw -DskipFrontend test -Dtest=TransferReportingTest` |
| One test method | `./mvnw -DskipFrontend test -Dtest=TransferReportingTest#transferIsNotIncome` |
| Frontend tests | `cd ui && npm run test` |
| One frontend file | `cd ui && npx vitest run src/App.test.tsx` |
| Full build | `./mvnw clean package` → `target/moneymind.jar` |
| Inspect the data | `sqlite3 ~/.moneymind/moneymind.db` |

`-DskipFrontend` must be a **system property on the command line**. Maven profile
activation does not see properties set by other profiles, which is why there is
no `-Pskip-frontend`.

**Use the two-process dev loop while iterating**, not the packaged jar. Run
`./run.sh dev` (backend on `:8080`) and `cd ui && npm run dev` (`:5173`) side by
side, and open **http://localhost:5173** — Vite proxies `/api` to Spring Boot, so
UI edits hot-reload in the browser and only backend edits need a restart.
`./run.sh` on its own repackages and serves both from `:8080`, which is the right
way to check the shipped artifact but means a full `mvn package` per change. The
two modes both bind `:8080`, so stop one before starting the other.

## Non-obvious facts worth not rediscovering

- **`org.flywaydb:flyway-database-sqlite` does not exist.** Flyway 11.7.2 (managed
  by Boot 3.5.6) ships SQLite support inside `flyway-core`. Only if Flyway is
  bumped past 12 does it move out, to `flyway-database-nc-sqlite`.
- **SQLite constraint violations arrive as `UncategorizedSQLException`**, not
  `DataIntegrityViolationException` — sqlite-jdbc does not populate SQLState.
  Assert on `DataAccessException` in tests.
- **`PRAGMA foreign_keys` is per connection.** It is set via Hikari
  `connection-init-sql`; checking it from the `sqlite3` CLI will show `0` and
  that is not a bug.
- Maven resolves through an internal Nexus mirror (`mirrorOf: *`), which does not
  proxy every artifact on Central.
- The ledger table is **`txn`**, because `TRANSACTION` is a SQLite keyword. The
  JPA entity is still `Transaction`.
- SQLite has **no `ALTER COLUMN` and no `DROP CONSTRAINT`**. Changing a column
  needs a create-copy-drop-rename migration, which is why the baseline schema is
  deliberately complete.
- **Test-wide property overrides live in `src/test/resources/application.properties`.**
  Boot loads it *in addition to* `application.yml` and gives `.properties` higher
  precedence, so single keys can be overridden without restating the whole YAML.
  A `src/test/resources/application.yml` would instead *replace* the main one,
  since test-classes precedes classes on the classpath.
- **The demo-data seeder must stay off in tests.** `moneymind.demo-data.enabled`
  is `true` for local running but overridden to `false` for tests: it otherwise
  fills the ledger before a test writes its own fixtures, and its `savings_goal`
  rows hold foreign keys into `account` that make `DELETE FROM account` fail.
  That is how `TransferReportingTest` first broke.
- **The UI router is hash-based (`#/budgets`), deliberately.** The built SPA is
  served as static files from inside the jar, so a reload on a real path would
  ask Spring Boot for a resource that does not exist and get a 404. A hash never
  reaches the server, so deep links work identically from `npm run dev` and from
  the packaged jar with no forwarding controller.
- **`Map.copyOf` does not preserve iteration order.** `Insight.facts` needs
  insertion order, so it copies through a `LinkedHashMap`.
- Amounts cross the API as `*Minor` integers with the currency named once per
  payload, never as JSON decimals — a JSON number is an IEEE double in the
  browser and `0.1` does not survive the trip.
- **sqlite-jdbc returns `Integer`, not `Long`, for an INTEGER column that fits
  in one.** `(Long) rs.getObject("transfer_id")` compiles, looks right and
  throws `ClassCastException` on real data. Use `rs.getLong` plus
  `rs.wasNull()`. This cost a debugging round on `TransactionService`.
- **`null` and `0` are different answers for a budget.** "Not budgeted" and
  "budgeted nothing" are distinct states, so `allocatedMinor` is nullable all
  the way to the UI. In a `LEFT JOIN`, read the column with `getObject` first —
  `getLong` flattens a missed join to a real-looking zero.
- **`SchemaInvariantsTest` pins the migration version, the table list and the
  exact category count.** Adding a migration means updating all three; that is
  the point, not friction. It is how a migration that silently failed to apply,
  or a seed category that vanished, gets noticed.
- The overall monthly budget is stored **per month** in `monthly_budget`, and
  reading a month with no row falls back to the most recent earlier one — so the
  figure is set once and carried forward. The response flags `inherited` so the
  UI never implies the user chose it for this month.

## Database

Flyway owns the schema; `spring.jpa.hibernate.ddl-auto` is `validate` and must
stay that way. Migrations are sequential: `V0001__baseline.sql`, then
`V0002__monthly_budget_and_everyday_categories.sql`. The dialect
(`org.hibernate.community.dialect.SQLiteDialect`) must be set explicitly.
Connection pool size is 1 — SQLite is single-writer and more connections cause
intermittent `SQLITE_BUSY`.

Data lives at `~/.moneymind/moneymind.db`, outside the repo and outside
`target/`, so neither `git` nor `mvn clean` can destroy it.

`updated_at` is maintained by **database triggers**, not Hibernate. Do not add
`@UpdateTimestamp` to those fields — the two mechanisms would fight.

`DemoDataSeeder` fills an empty ledger with six months of plausible activity so
the dashboard can be judged before manual entry exists. It is a **development
aid meant to be deleted** at Phase 1, runs only when `txn` is completely empty
so it can never touch real data, and writes every amount through
`Money.fromMajor` so the exponent comes from the currency. Turn it off with
`moneymind.demo-data.enabled=false`; to clear what it wrote, delete
`~/.moneymind/moneymind.db` and restart.

## Dashboard and charts

The main screen is one request (`GET /api/dashboard`) read in a single
transaction, so no figure can disagree with another and the page has one loading
state instead of six.

Chart decisions worth not re-litigating:

- **Spending by category is horizontal bars in a single hue.** Length already
  encodes magnitude; grading the colour by size would encode it twice. One series
  means no legend — the heading names what is plotted.
- **The trend chart draws the current month as partial** (faded, labelled "so
  far", excluded from the "highest month" comparison). See invariant 11.
- **Budgets are meters**, not a pie: a ratio against a limit.
- **The monthly budget is a donut** — the one place a ring is right, because the
  question really is "how much of the circle is left". It sits beside the bar
  chart, which compares categories far more precisely than angles can. Slices
  cap at five named categories plus "Other" plus the remainder; the remainder is
  grey because it is not a category. When overspent there is no remainder slice
  and the ring becomes the spending itself — a 110%-full circle just looks full.
- **A slice's hue belongs to its category, never to its rank** (`FIXED_SLOTS` in
  `BudgetDonut.tsx`). Colouring by position would repaint every category the
  moment the ranking changed, and month-over-month comparison would be
  meaningless. The six categorical slots are validated: worst adjacent CVD ΔE
  9.1 light / 8.4 dark, normal-vision ΔE 19.6 / 19.3. Three light-mode slices
  fall under 3:1 contrast and one pair separates by only ΔE 5.8 under
  tritanopia, which is why the legend names every slice with its amount and
  share — that visible text is the required relief, not decoration.
- Marks follow fixed specs: bars capped at 24px thick with a 4px rounded data-end
  and a square baseline, hairline solid gridlines, values labelled selectively
  rather than on every point, and a table view under the trend chart so no value
  is reachable only by hover.
- The accent green is the only data colour (5.99:1 on the light surface, 7.46:1 on
  the dark). The status trio is separate, non-themed, and always paired with text.
- Colours are defined once as tokens in `ui/src/index.css` and redefined only in
  the dark-mode block; components reference tokens, never hex.

## The invariants that must not be broken

These are the difference between a finance app and a plausible-looking one.

1. **Money is `long` minor units plus a currency code.** Never `double`, never
   SQLite `REAL`. The exponent is per currency (EUR 2, JPY 0, TND 3) and comes
   from `Currency.getDefaultFractionDigits()` — never hardcode 100.
2. **`Money.allocate(int...)` is the only place money is divided.** It uses
   largest-remainder so the parts sum back exactly. No inline `Math.round`.
3. **Never sum across currencies.** `Money.plus` throws; aggregate queries
   `GROUP BY currency`.
4. **`txn.kind` is stored, never derived from the sign.** A refund is a positive
   amount in an expense category and must not become income. Budget actuals use
   `SUM(amount_minor)`, not `SUM(ABS(...))`, so refunds reduce spend.
5. **A transfer is a `transfer` row plus exactly two `txn` legs.** Balances use
   every row; income and expense **always** filter `kind <> 'TRANSFER'`. Transfer
   legs never carry a category. `TransferReportingTest` guards this and is the
   most important test in the repository.
6. **Amounts are signed from the account's perspective** — negative means money
   left. A credit card's negative balance means "you owe". Flip signs only in the
   presentation layer.
7. **`txn.currency` must equal `account.currency`**, enforced by a trigger.
8. **`dedup_hash` is deliberately not unique.** Two identical coffees on the same
   day are legitimate, so it feeds a review queue. Only bank-supplied
   `external_id` is unique.
9. **Recurring predictions are never `txn` rows.** They are `recurring_occurrence`
   rows with a status; a forecast in the ledger would poison every balance.
10. **The app must work fully with `moneymind.ai.enabled=false`.** Every number
    shown comes from an `Insight`'s facts, never parsed out of model prose.
11. **Never compare a partial period against a complete one.** The current month
    is compared against the *same number of elapsed days* of the previous month,
    and a partial month is drawn as visibly partial in the trend chart. Comparing
    four days with thirty-one reports an improvement every month until the last
    week of it — flattering, and false.
12. **A rule that cannot say something true says nothing.** `InsightRule` returns
    `Optional.empty()` rather than inventing an observation to fill the strip: no
    baseline month, a single category, a goal with no deadline. A confident
    sentence about two transactions costs more trust than a blank panel.
13. **Status must never be carried by colour alone.** Budget state ships as text
    plus a distinct shape. The warning amber measures 1.83:1 on the light
    surface, so the words are the accessible channel, not decoration.
14. **Spending is derived from the ledger and is never writable.** There is no
    "amount spent" field anywhere — not in the API, not on the Budgets screen.
    A typed-in spend total would immediately contradict the account balances and
    the transaction list meant to explain it, with nothing to say which was
    right. To change what was spent, record a transaction.
15. **The UI decides the sign; the API takes it already signed.** The entry form
    collects a positive amount plus a direction — *Money out* → negative
    `EXPENSE`, *Money in* → positive `INCOME`, *Refund* → **positive**
    `EXPENSE` — and `toKindAndSign` is the single place that mapping lives. The
    API never infers a sign, because a positive expense is a refund and that has
    to stay expressible.
16. **A transfer is created only through `POST /api/transactions/transfer`.**
    The transaction endpoint rejects `kind: TRANSFER` outright. One leg cannot be
    edited alone, and deleting either leg deletes the whole transfer — half a
    transfer makes money appear from nowhere.
17. **Amounts typed by a person are parsed digit-by-digit, never by
    multiplying.** `parseFloat('12.34') * 100` is `1234.0000000000002`. Excess
    precision for the currency is rejected rather than rounded away.

## Testing

JUnit 5 with the `*Test` suffix and test packages mirroring main; AssertJ comes
via `spring-boot-starter-test`. Frontend uses Vitest + React Testing Library.

Repository and schema tests run against a **real temporary SQLite file** (see
`@DynamicPropertySource` in `SchemaInvariantsTest`), not an in-memory stand-in —
dialect quirks, partial indexes and trigger behaviour are exactly what would
otherwise only fail at runtime.

Before writing any reporting query, check it against `TransferReportingTest`.
`DashboardReportingTest` then asserts the same invariants against the queries the
dashboard actually issues — a transfer leaking into a total and a refund read as
income are the two failures it exists to catch.

The insight rules are tested as plain objects (`InsightRulesTest`), constructing
an `AnalysisContext` literal with no Spring context and no database. That is the
payoff for keeping them annotation-free.

## Optional LLM layer

`com.anthropic:anthropic-java`, client from `AnthropicOkHttpClient.fromEnv()`
reading `ANTHROPIC_API_KEY`, model `claude-opus-5` with `effort = LOW` (a
rewording task). Disabled by default via `moneymind.ai.enabled`. The phraser
receives an already-computed `Insight` and returns prose only; it must never be
the source of a number, and a timeout or failure falls back to the template.

Do NOT implement:

Real banking integrations
Real payments
Real financial advice
Complex authentication
Production database
External financial APIs`