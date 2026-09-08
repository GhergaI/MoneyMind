-- MoneyMind baseline schema.
--
-- Conventions:
--   * Money is ALWAYS integer minor units (`*_minor`) plus a 3-letter currency code.
--     Never REAL, never DECIMAL. The minor-unit exponent is per-currency
--     (JPY=0, EUR=2, TND=3) and is derived in Java from Currency.getDefaultFractionDigits().
--   * Dates and timestamps are ISO-8601 TEXT.
--   * The ledger table is `txn`, not `transaction` — TRANSACTION is a SQLite keyword
--     and would need quoting everywhere. The JPA entity is still named Transaction.
--   * Amounts are signed from the account's perspective: negative means money left.
--     A liability account (credit card) therefore carries a negative balance
--     meaning "you owe". Signs are flipped only in the presentation layer.
--   * Account balance = account.opening_balance_minor + SUM(txn.amount_minor).
--   * Income/expense reporting ALWAYS filters `kind <> 'TRANSFER'`.

-- ---------------------------------------------------------------------------
-- Accounts: money containers you own (current, savings, cash, credit card).
-- Not user accounts — there is no login and no user table.
-- ---------------------------------------------------------------------------
CREATE TABLE account (
    id                    INTEGER PRIMARY KEY AUTOINCREMENT,
    name                  TEXT    NOT NULL,
    type                  TEXT    NOT NULL CHECK (type IN ('CURRENT','SAVINGS','CASH','CREDIT_CARD','INVESTMENT')),
    currency              TEXT    NOT NULL,
    opening_balance_minor INTEGER NOT NULL DEFAULT 0,
    archived_at           TEXT,
    sort_order            INTEGER NOT NULL DEFAULT 0,
    created_at            TEXT    NOT NULL DEFAULT (datetime('now')),
    updated_at            TEXT    NOT NULL DEFAULT (datetime('now'))
);
CREATE UNIQUE INDEX ux_account_name ON account(name) WHERE archived_at IS NULL;

-- ---------------------------------------------------------------------------
-- Categories: hierarchical, but max depth 2 (enforced in the domain — SQLite
-- CHECK cannot traverse a self-reference). Never deleted; archived instead, so
-- historical transactions never orphan. `system_key` lets rules refer to
-- GROCERIES semantically even after the display name is changed.
-- ---------------------------------------------------------------------------
CREATE TABLE category (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    parent_id   INTEGER REFERENCES category(id),
    name        TEXT    NOT NULL,
    kind        TEXT    NOT NULL CHECK (kind IN ('INCOME','EXPENSE')),
    icon        TEXT,
    color       TEXT,
    system_key  TEXT,
    archived_at TEXT,
    sort_order  INTEGER NOT NULL DEFAULT 0,
    created_at  TEXT    NOT NULL DEFAULT (datetime('now')),
    updated_at  TEXT    NOT NULL DEFAULT (datetime('now'))
);
CREATE UNIQUE INDEX ux_category_system_key ON category(system_key) WHERE system_key IS NOT NULL;
CREATE INDEX idx_category_parent ON category(parent_id);

-- ---------------------------------------------------------------------------
-- Transfers: a grouping entity over exactly two txn legs. This is what keeps a
-- transfer between your own accounts out of income and expense totals while
-- still moving both balances. PENDING_MATCH allows a single-leg transfer to
-- exist temporarily, which CSV import needs.
-- ---------------------------------------------------------------------------
CREATE TABLE transfer (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    note       TEXT,
    status     TEXT NOT NULL DEFAULT 'COMPLETE' CHECK (status IN ('COMPLETE','PENDING_MATCH')),
    created_at TEXT NOT NULL DEFAULT (datetime('now'))
);

-- ---------------------------------------------------------------------------
-- Import bookkeeping. Unused until the CSV import phase, but present from the
-- baseline so import never forces a table rebuild (SQLite has no ALTER COLUMN)
-- on a database that by then holds real spending history.
-- ---------------------------------------------------------------------------
CREATE TABLE import_source_profile (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    name              TEXT    NOT NULL,
    source            TEXT    NOT NULL CHECK (source IN ('CSV','OFX','BANK_API')),
    column_mapping    TEXT    NOT NULL,   -- JSON: per-bank column layout held as DATA, not code
    date_format       TEXT,
    decimal_separator TEXT,
    created_at        TEXT    NOT NULL DEFAULT (datetime('now'))
);
CREATE UNIQUE INDEX ux_import_source_profile_name ON import_source_profile(name);

CREATE TABLE import_batch (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    account_id     INTEGER NOT NULL REFERENCES account(id),
    profile_id     INTEGER REFERENCES import_source_profile(id),
    source         TEXT    NOT NULL CHECK (source IN ('CSV','OFX','BANK_API')),
    filename       TEXT,
    row_count      INTEGER NOT NULL DEFAULT 0,
    imported_count INTEGER NOT NULL DEFAULT 0,
    status         TEXT    NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','REVIEWING','COMMITTED','REVERSED')),
    created_at     TEXT    NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX idx_import_batch_account ON import_batch(account_id);

-- ---------------------------------------------------------------------------
-- The ledger.
-- ---------------------------------------------------------------------------
CREATE TABLE txn (
    id                    INTEGER PRIMARY KEY AUTOINCREMENT,
    account_id            INTEGER NOT NULL REFERENCES account(id),
    category_id           INTEGER REFERENCES category(id),
    transfer_id           INTEGER REFERENCES transfer(id),

    -- `kind` is STORED, never derived from the sign of the amount. A refund is a
    -- positive amount in an expense category and must not become income.
    kind                  TEXT    NOT NULL CHECK (kind IN ('INCOME','EXPENSE','TRANSFER')),
    amount_minor          INTEGER NOT NULL,
    currency              TEXT    NOT NULL,

    booked_on             TEXT    NOT NULL,   -- the ONE date all reporting uses
    value_on              TEXT,               -- bank value date, informational

    description           TEXT,
    counterparty          TEXT,
    notes                 TEXT,

    -- Multi-currency hooks: cheap now, unbackfillable later.
    original_amount_minor INTEGER,
    original_currency     TEXT,
    fx_rate               TEXT,

    -- Import hooks.
    source                TEXT    NOT NULL DEFAULT 'MANUAL' CHECK (source IN ('MANUAL','CSV','OFX','BANK_API')),
    external_id           TEXT,
    import_batch_id       INTEGER REFERENCES import_batch(id),
    dedup_hash            TEXT,
    raw_payload           TEXT,
    manually_edited       INTEGER NOT NULL DEFAULT 0,
    duplicate_of_id       INTEGER REFERENCES txn(id),

    created_at            TEXT    NOT NULL DEFAULT (datetime('now')),
    updated_at            TEXT    NOT NULL DEFAULT (datetime('now')),

    -- A row is a transfer leg if and only if it belongs to a transfer.
    CONSTRAINT ck_txn_transfer_kind CHECK ((kind = 'TRANSFER') = (transfer_id IS NOT NULL)),
    -- Transfers never consume a budget, so they never carry a category.
    CONSTRAINT ck_txn_transfer_uncategorised CHECK (kind <> 'TRANSFER' OR category_id IS NULL)
);

CREATE INDEX idx_txn_account_booked ON txn(account_id, booked_on);
CREATE INDEX idx_txn_booked         ON txn(booked_on);
CREATE INDEX idx_txn_category       ON txn(category_id);
CREATE INDEX idx_txn_transfer       ON txn(transfer_id);
CREATE INDEX idx_txn_batch          ON txn(import_batch_id);

-- Tier 1 dedup: authoritative when the bank supplies a stable id.
CREATE UNIQUE INDEX ux_txn_external_id ON txn(account_id, source, external_id)
    WHERE external_id IS NOT NULL;

-- Tier 2 dedup: DELIBERATELY NOT UNIQUE. Two identical coffees on the same day
-- are legitimate, so the hash feeds a review queue rather than a constraint.
CREATE INDEX idx_txn_dedup_hash ON txn(dedup_hash);

CREATE TABLE import_staging_row (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    import_batch_id     INTEGER NOT NULL REFERENCES import_batch(id),
    line_number         INTEGER NOT NULL,
    raw_payload         TEXT    NOT NULL,
    parsed_booked_on    TEXT,
    parsed_amount_minor INTEGER,
    parsed_description  TEXT,
    dedup_hash          TEXT,
    decision            TEXT    NOT NULL DEFAULT 'PENDING' CHECK (decision IN ('PENDING','IMPORT','SKIP_DUPLICATE')),
    created_txn_id      INTEGER REFERENCES txn(id)
);
CREATE INDEX idx_import_staging_batch ON import_staging_row(import_batch_id);

-- ---------------------------------------------------------------------------
-- Budgets. `budget` holds intent; `budget_period` materialises each concrete
-- window. Computing periods on the fly cannot express "I raised the groceries
-- budget in June", and storing rollover_in_minor at close stops every query
-- recursing over all history.
-- ---------------------------------------------------------------------------
CREATE TABLE budget (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    category_id INTEGER NOT NULL REFERENCES category(id),
    default_amount_minor  INTEGER NOT NULL,
    period_type TEXT    NOT NULL DEFAULT 'MONTHLY' CHECK (period_type IN ('MONTHLY','WEEKLY','CUSTOM')),
    anchor_day  INTEGER NOT NULL DEFAULT 1 CHECK (anchor_day BETWEEN 1 AND 31),
    rollover    INTEGER NOT NULL DEFAULT 0,
    currency    TEXT    NOT NULL,
    active_from TEXT    NOT NULL,
    active_to   TEXT,
    created_at  TEXT    NOT NULL DEFAULT (datetime('now')),
    updated_at  TEXT    NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX idx_budget_category ON budget(category_id);

CREATE TABLE budget_period (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    budget_id         INTEGER NOT NULL REFERENCES budget(id),
    starts_on         TEXT    NOT NULL,
    ends_on           TEXT    NOT NULL,
    allocated_minor   INTEGER NOT NULL,
    rollover_in_minor INTEGER NOT NULL DEFAULT 0,
    closed_at         TEXT,
    CONSTRAINT ck_budget_period_order CHECK (ends_on >= starts_on)
);
CREATE UNIQUE INDEX ux_budget_period ON budget_period(budget_id, starts_on);

-- ---------------------------------------------------------------------------
-- Savings goals. Money is fungible: a goal either mirrors a real account
-- balance or is an earmark over a shared account, and those compute
-- differently. Earmarks let one transfer split across several goals.
-- ---------------------------------------------------------------------------
CREATE TABLE savings_goal (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    name         TEXT    NOT NULL,
    target_minor INTEGER NOT NULL,
    currency     TEXT    NOT NULL,
    target_date  TEXT,
    account_id   INTEGER REFERENCES account(id),
    funding_mode TEXT    NOT NULL CHECK (funding_mode IN ('ACCOUNT_BALANCE','EARMARKED')),
    archived_at  TEXT,
    created_at   TEXT    NOT NULL DEFAULT (datetime('now')),
    updated_at   TEXT    NOT NULL DEFAULT (datetime('now')),
    CONSTRAINT ck_savings_goal_account CHECK (funding_mode <> 'ACCOUNT_BALANCE' OR account_id IS NOT NULL)
);

CREATE TABLE savings_contribution (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    goal_id      INTEGER NOT NULL REFERENCES savings_goal(id),
    txn_id       INTEGER NOT NULL REFERENCES txn(id),
    amount_minor INTEGER NOT NULL,
    created_at   TEXT    NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX idx_savings_contribution_goal ON savings_contribution(goal_id);
CREATE UNIQUE INDEX ux_savings_contribution ON savings_contribution(goal_id, txn_id);

-- ---------------------------------------------------------------------------
-- Recurring payments and subscriptions.
-- Predictions are materialised occurrence rows WITH A STATUS. They are never
-- written as txn rows — a forecast in the ledger would poison every balance
-- and budget query. Comparing actual_amount_minor across occurrences is how a
-- price rise is detected; a past-due PREDICTED row becoming MISSED is how a
-- silently-cancelled subscription is detected.
-- ---------------------------------------------------------------------------
CREATE TABLE recurring_series (
    id                    INTEGER PRIMARY KEY AUTOINCREMENT,
    name                  TEXT    NOT NULL,
    account_id            INTEGER NOT NULL REFERENCES account(id),
    category_id           INTEGER REFERENCES category(id),
    kind                  TEXT    NOT NULL CHECK (kind IN ('INCOME','EXPENSE')),
    expected_amount_minor INTEGER NOT NULL,
    currency              TEXT    NOT NULL,
    counterparty          TEXT,

    freq                  TEXT    NOT NULL CHECK (freq IN ('WEEKLY','MONTHLY','QUARTERLY','YEARLY')),
    interval_count        INTEGER NOT NULL DEFAULT 1 CHECK (interval_count >= 1),
    -- -1 means "last day of the month", which is why this is not a plain 1..31
    by_month_day          INTEGER CHECK (by_month_day = -1 OR by_month_day BETWEEN 1 AND 31),
    by_weekday            INTEGER CHECK (by_weekday BETWEEN 1 AND 7),
    anchor_date           TEXT    NOT NULL,
    next_due_on           TEXT,

    amount_tolerance_pct  INTEGER NOT NULL DEFAULT 10,
    day_tolerance_days    INTEGER NOT NULL DEFAULT 3,

    status                TEXT    NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','PAUSED','ENDED')),
    last_seen_on          TEXT,
    created_at            TEXT    NOT NULL DEFAULT (datetime('now')),
    updated_at            TEXT    NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX idx_recurring_series_account ON recurring_series(account_id);
CREATE INDEX idx_recurring_series_due     ON recurring_series(next_due_on);

CREATE TABLE recurring_occurrence (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    series_id           INTEGER NOT NULL REFERENCES recurring_series(id),
    due_on              TEXT    NOT NULL,
    status              TEXT    NOT NULL DEFAULT 'PREDICTED' CHECK (status IN ('PREDICTED','MATCHED','MISSED','SKIPPED')),
    matched_txn_id      INTEGER REFERENCES txn(id),
    actual_amount_minor INTEGER,
    created_at          TEXT    NOT NULL DEFAULT (datetime('now')),
    updated_at          TEXT    NOT NULL DEFAULT (datetime('now')),
    CONSTRAINT ck_recurring_matched CHECK ((status = 'MATCHED') = (matched_txn_id IS NOT NULL))
);
CREATE UNIQUE INDEX ux_recurring_occurrence ON recurring_occurrence(series_id, due_on);
CREATE INDEX idx_recurring_occurrence_due  ON recurring_occurrence(due_on, status);

-- ---------------------------------------------------------------------------
-- Starter categories. Currency-independent, so they are safe to seed here.
-- `system_key` is the stable handle insight rules use.
-- ---------------------------------------------------------------------------
INSERT INTO category (name, kind, system_key, sort_order) VALUES
    ('Salary',          'INCOME',  'SALARY',         10),
    ('Other income',    'INCOME',  'OTHER_INCOME',   20),
    ('Groceries',       'EXPENSE', 'GROCERIES',      30),
    ('Rent & bills',    'EXPENSE', 'HOUSING',        40),
    ('Utilities',       'EXPENSE', 'UTILITIES',      50),
    ('Transport',       'EXPENSE', 'TRANSPORT',      60),
    ('Eating out',      'EXPENSE', 'EATING_OUT',     70),
    ('Subscriptions',   'EXPENSE', 'SUBSCRIPTIONS',  80),
    ('Health',          'EXPENSE', 'HEALTH',         90),
    ('Shopping',        'EXPENSE', 'SHOPPING',      100),
    ('Entertainment',   'EXPENSE', 'ENTERTAINMENT', 110),
    ('Travel',          'EXPENSE', 'TRAVEL',        120),
    ('Fees & charges',  'EXPENSE', 'FEES',          130),
    ('Uncategorised',   'EXPENSE', 'UNCATEGORISED', 999);

-- ---------------------------------------------------------------------------
-- FX rates. txn carries original_amount_minor/original_currency/fx_rate for the
-- rate that applied at booking time; this table is what reporting-time
-- conversion reads. Rates are TEXT decimals — SQLite REAL would lose precision.
-- ---------------------------------------------------------------------------
CREATE TABLE fx_rate (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    base_ccy   TEXT NOT NULL,
    quote_ccy  TEXT NOT NULL,
    rate_date  TEXT NOT NULL,
    rate       TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    CONSTRAINT ck_fx_rate_distinct CHECK (base_ccy <> quote_ccy)
);
CREATE UNIQUE INDEX ux_fx_rate ON fx_rate(base_ccy, quote_ccy, rate_date);

-- ---------------------------------------------------------------------------
-- A transaction must be denominated in its account's currency. This is a
-- cross-table rule, so it cannot be a CHECK constraint; a trigger enforces it
-- on every write path, including raw SQL and imports. Foreign-currency spending
-- is recorded in the account currency with the original kept in
-- original_amount_minor/original_currency.
-- ---------------------------------------------------------------------------
CREATE TRIGGER trg_txn_currency_matches_account_insert
BEFORE INSERT ON txn
FOR EACH ROW
WHEN NEW.currency <> (SELECT currency FROM account WHERE id = NEW.account_id)
BEGIN
    SELECT RAISE(ABORT, 'txn.currency must equal account.currency');
END;

CREATE TRIGGER trg_txn_currency_matches_account_update
BEFORE UPDATE OF currency, account_id ON txn
FOR EACH ROW
WHEN NEW.currency <> (SELECT currency FROM account WHERE id = NEW.account_id)
BEGIN
    SELECT RAISE(ABORT, 'txn.currency must equal account.currency');
END;

-- ---------------------------------------------------------------------------
-- updated_at maintenance. A column DEFAULT fires on INSERT only, so without
-- these the column would silently claim every row was last touched when it was
-- created. Triggers rather than Hibernate @UpdateTimestamp, so the guarantee
-- holds for raw SQL and migrations too — do NOT also annotate these fields, or
-- the two mechanisms will fight.
-- The WHEN guard makes an explicit updated_at write win and avoids recursion.
-- ---------------------------------------------------------------------------
CREATE TRIGGER trg_account_updated_at AFTER UPDATE ON account FOR EACH ROW
WHEN NEW.updated_at = OLD.updated_at
BEGIN UPDATE account SET updated_at = datetime('now') WHERE id = NEW.id; END;

CREATE TRIGGER trg_category_updated_at AFTER UPDATE ON category FOR EACH ROW
WHEN NEW.updated_at = OLD.updated_at
BEGIN UPDATE category SET updated_at = datetime('now') WHERE id = NEW.id; END;

CREATE TRIGGER trg_txn_updated_at AFTER UPDATE ON txn FOR EACH ROW
WHEN NEW.updated_at = OLD.updated_at
BEGIN UPDATE txn SET updated_at = datetime('now') WHERE id = NEW.id; END;

CREATE TRIGGER trg_budget_updated_at AFTER UPDATE ON budget FOR EACH ROW
WHEN NEW.updated_at = OLD.updated_at
BEGIN UPDATE budget SET updated_at = datetime('now') WHERE id = NEW.id; END;

CREATE TRIGGER trg_savings_goal_updated_at AFTER UPDATE ON savings_goal FOR EACH ROW
WHEN NEW.updated_at = OLD.updated_at
BEGIN UPDATE savings_goal SET updated_at = datetime('now') WHERE id = NEW.id; END;

CREATE TRIGGER trg_recurring_series_updated_at AFTER UPDATE ON recurring_series FOR EACH ROW
WHEN NEW.updated_at = OLD.updated_at
BEGIN UPDATE recurring_series SET updated_at = datetime('now') WHERE id = NEW.id; END;

CREATE TRIGGER trg_recurring_occurrence_updated_at AFTER UPDATE ON recurring_occurrence FOR EACH ROW
WHEN NEW.updated_at = OLD.updated_at
BEGIN UPDATE recurring_occurrence SET updated_at = datetime('now') WHERE id = NEW.id; END;
