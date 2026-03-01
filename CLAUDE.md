# CLAUDE.md
> Project briefing for Claude Code — read this at the start of every session and after every /compact.

---

## Project Overview

**App:** DairyPOS — A full Android ERP system for a dairy farming business.
**Purpose:** Manages complete daily business operations including procurement, production, inventory, sales, and accounting. Built by the business owner for their own dairy farm.
**Status:** Core ERP operations complete. Accounting automation in active development.
**Target:** Publishing within 2-3 months.

---

## Tech Stack

- **Platform:** Android (Kotlin)
- **Database:** SQLite via custom `DatabaseHelper.kt`
- **Architecture:** Repository pattern — each domain has its own repository class
- **Package root:** `com.example.dairypos`
- **Key packages:**
  - `com.example.dairypos.data.repository.erp` — all repository classes
  - `com.example.dairypos` — DatabaseHelper and core helpers

---

## Frontend Assets / Modules

The WebView frontend lives in:
`app/src/main/assets/`

Screen JS files follow the naming pattern `s{N}_{screenId}.js` where N is the domain section number:
- `s0_` = Accounting (accounts, reports, journal)
- `s1_` = Navigation stubs / dashboard
- `s2_` = Purchasing & suppliers
- `s2_ap_payment.js` = AP Payment
- `s3_` = Sales
- `s4_` = Stock consumption
- `s5_` = Customers & invoices
- `s6_` = Payments
- `s7_` = Milk production
- `s8_` = Inventory settings (products, stock, UOMs)
- `s9_` = Expenses
- `s10_` = Query tool
- `s11_` = Operational / sync

Templates live in `assets/templates/`.

**STRICT — NEVER read these large third-party library files. They are external dependencies with no project code:**
- `app/src/main/assets/codemirror/` — entire folder (CodeMirror editor library)
- `app/src/main/assets/css/awsomef.css` — FontAwesome icon library
- `app/src/main/assets/css/bootstrap.css` — Bootstrap CSS framework

Reading these files wastes context tokens and provides no useful project information.

---

## Critical Code Conventions

### Extension Functions
All SQLite extension functions live in:
```
com/example/dairypos/data/repository/erp/SqliteExtensions.kt
```
Do NOT define extension functions inside `DatabaseHelper.kt` or any repository class. They must be top-level in `SqliteExtensions.kt` to be visible across all repositories.

Current extensions: `singleInt()`, `forEach()`
Add new extensions to `SqliteExtensions.kt` only.

### Repository Pattern
Each domain area has its own repository class in `data.repository.erp`.
- `ProcurementRepository.kt` — purchasing, AP entries
- Add new repositories following the same pattern
- Repositories receive `DatabaseHelper` via constructor injection

### DatabaseHelper
- `helper.writableDatabase` → use for INSERT, UPDATE, DELETE
- `helper.readableDatabase` → use for SELECT queries only
- All SQL extension functions accept `Array<String>` for args, never `arrayOf()` with non-string types

---

## Accounting System Architecture

### How Journal Entries Work
```
Operational Event
    → accountingTransaction (log)
    → JournalEntryEngine.postJournalEntries()
    → looks up AccountingJournalsMap by transactionType + subType
    → inserts into journalEntries (Dr/Cr pairs)
```

### Key Tables
- `chartAccounts` — Chart of Accounts (COA)
- `standardAccountTypes` — Asset, Liability, Equity, Income, Expense
- `TransactionTypes` — named event types that drive journal automation
- `AccountingJournalsMap` — maps TransactionType+subType → debitAccountId/creditAccountId
- `accountingTransaction` — source log for every ERP event
- `journalEntries` — final double-entry ledger

### In-Progress: Accrual Automation
- `T_AP_ACCRUAL_HEADS` — static COA classifier for reporting (being implemented)
- `parentJournalId` — self-referencing FK on `journalEntries` for audit trail chaining (being implemented)
- EOM entries fired by a manual "Close Month" button (user-initiated)

### Modules Registry

`modulesRegistry` catalogs the four ERP modules and links them to their `transactionTypes` via a `moduleId` FK.

**Tables involved:**
- `modulesRegistry` — 4 rows: Procurement, Sales, Production, Expenses
- `transactionTypes` — has a `moduleId INTEGER REFERENCES modulesRegistry(id)` column (added in DB_VERSION 2)
- `accountingJournalsMap` — unchanged; still keyed by `transactionTypeId`

**Module → TransactionType ownership:**

| Module | TransactionTypes |
|---|---|
| Procurement | Purchase, PayablePayment |
| Sales | Sale, BatchSoldCogs, Invoice, ReceivePayment |
| Production | Production, Mix, FeedUse, ProductionExpense |
| Expenses | Expense |

**Admin screen:** `modules_registry` (s0) — accessible from Reports stub under "Modules Setup"
- 3-panel mobile UI: Modules → Transaction Types → Journal Mappings
- Allows adding/editing/deleting `accountingJournalsMap` rows per TransactionType
- Shows completeness badges and flags unconfigured TransactionTypes

**Repository:** `data.repository.accounting.ModulesRepository`
Methods: `getModules()`, `getModuleDetail(moduleId)`, `getMappingsForTxnType(txnTypeId)`,
`saveMapping(json)`, `deleteMapping(id)`, `getUnmappedSummary()`

---

### AccountingJournalsMap Sequence Logic
`sequence` orders multiple journal entries for a single transaction event. Entries are **evidence-based** — a sequence row only fires if the transaction payload justifies it. Accounting does not allow automatic chaining without evidence.curre


**General rules for sequence:**
- `condition = NULL` → always fires (unconditional)
- `condition = 'cashPaid=true'` → fires only if payload contains that flag (not yet implemented — reserved in `condition` column)
- Do NOT add new chained sequences without a corresponding payload condition — every entry must have evidence

---

## Known Issues (Fix Before Any Migration)

- `TransactionTypes` INSERT has a syntax error: `('ProductionExpense'),0` → remove the stray `0`
- COA codes `7000` and `7001` are both named "Vet Expense" — duplicate, needs resolution
- `PayablePayment` TransactionType is now implemented — AP Payment screen (`s2_ap_payment.js`) is complete; remove seq 2 from Purchase mapping
- `Invoice` TransactionType has no `accountingJournalsMap` entry — configure it via the Modules Setup screen (`modules_registry`)

---

## Developer Working Style & Preferences

- **Do not over-engineer** — keep solutions simple and direct. complexity is deferred deliberately.
- **No unsolicited refactoring** — only change what is asked. do not restructure code that isn't broken.
- **Discuss before implementing** — for architectural decisions, discuss in Q&A first, then implement.
- **Application layer first** — DB constraints and security rules are intentionally deferred to a later phase. do not add them unless asked.
- **Single responsibility** — one file per concern. don't combine unrelated logic.
- **Kotlin idioms** — use Kotlin-idiomatic code. prefer extension functions, `use {}` for cursor handling, named parameters where it aids clarity.
- **No new dependencies** — do not suggest adding new libraries unless explicitly asked.
- **SQL formatting** — use `trimIndent()` for multiline SQL strings in Kotlin. keep SQL readable.
- **Evidence-based entries** — journal entries must always be tied to a real transaction payload or user-initiated event. never assume or auto-chain without evidence.

---

## GitHub CLI (gh)

`gh` is installed and authenticated as **AtiqDev** (keyring, persistent). Claude can use `gh` for GitHub operations (PRs, issues, releases, etc.) without any login step.

---

## Session Startup Checklist

When starting a new session, Claude should:
1. Read this file and wait for user prompt.
2. Do NOT make any code changes until the task is clearly understood

## Context Management

- When context reaches ~70% (`/context` to check) → remind developer to consider `/compact` or new session
- When starting a fresh session always re-read `CLAUDE.md` before touching any code

---

## Git Safety Protocol

Before any complex change, refactor, or multi-file edit, Claude must preserve the current working state via git stash:

```bash
# Step 1: Save all changes (tracked + untracked) as a named backup
git stash push -u -m "backup: <brief description of what is about to change>"

# Step 2: Immediately re-apply so the working directory is restored unchanged
git stash apply stash@{0}
```

**Why two steps:** `git stash push -u` cleans the working directory (including untracked files) as a side effect. The `apply` immediately restores everything, so work continues uninterrupted while the stash entry remains as a permanent backup.

**Rules:**
- ALWAYS run both steps together — stash then apply — so no files disappear from the working directory
- ALWAYS use `git stash apply` — **NEVER use `git stash pop`**
- `git stash apply` preserves the stash entry as a permanent backup copy in the stash list
- `git stash pop` destroys the stash entry after applying — no recovery if something goes wrong
- Use a descriptive stash message so backups are identifiable (`backup: before modularizing ProcurementRepository`)
- If `git stash apply` reports conflicts, stop and notify the developer before proceeding

**When to stash:**
- Before making changes to any code that is depended upon by components **outside the scope of the current task** — i.e. if touching shared code (extension functions, DatabaseHelper, base repositories, AccountingJournalsMap logic, JournalEntryEngine) that other unrelated modules consume, stash first
- If the change is fully self-contained within the component being worked on and no outside component depends on what is being modified, stash is not required