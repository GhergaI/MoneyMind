-- Interactive budgeting: an overall monthly amount, plus the everyday spending
-- categories a household actually thinks in.
--
-- Why a separate table rather than a column somewhere: the overall budget is a
-- per-month fact, exactly like `budget_period` is for a category budget.
-- Storing a single current value would make "what did I budget last March"
-- unanswerable the moment the number is changed, and every month-over-month
-- comparison would silently use today's figure for last year's month.

CREATE TABLE monthly_budget (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    period_month TEXT    NOT NULL,   -- 'YYYY-MM'; ISO text sorts chronologically
    amount_minor INTEGER NOT NULL CHECK (amount_minor >= 0),
    currency     TEXT    NOT NULL,
    created_at   TEXT    NOT NULL DEFAULT (datetime('now')),
    updated_at   TEXT    NOT NULL DEFAULT (datetime('now')),
    -- Guards against '2026-9' or a full date being written by mistake; the
    -- month-lookup query relies on these strings being exactly comparable.
    CONSTRAINT ck_monthly_budget_month CHECK (period_month LIKE '____-__')
);

-- One overall budget per month per currency. Amounts are never summed across
-- currencies, so the currency is part of the identity rather than an attribute.
CREATE UNIQUE INDEX ux_monthly_budget ON monthly_budget(currency, period_month);

CREATE TRIGGER trg_monthly_budget_updated_at AFTER UPDATE ON monthly_budget FOR EACH ROW
WHEN NEW.updated_at = OLD.updated_at
BEGIN UPDATE monthly_budget SET updated_at = datetime('now') WHERE id = NEW.id; END;

-- ---------------------------------------------------------------------------
-- Everyday spending categories.
--
-- These sit alongside the baseline set rather than replacing it: categories are
-- never deleted, only archived, because historical transactions must never
-- orphan. `Health` already exists in the baseline with system_key HEALTH and is
-- deliberately not duplicated here.
--
-- The sort_order values interleave with the baseline's (which uses steps of 10)
-- so the combined list reads sensibly without renumbering existing rows.
-- ---------------------------------------------------------------------------
INSERT INTO category (name, kind, system_key, sort_order) VALUES
    ('Food',        'EXPENSE', 'FOOD',         25),
    ('Household',   'EXPENSE', 'HOUSEHOLD',    35),
    ('Personal',    'EXPENSE', 'PERSONAL',     95),
    ('Unpredicted', 'EXPENSE', 'UNPREDICTED', 135);
