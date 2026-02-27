# DairyPOS Accounting System — Current State

> Reference document for the accounting layer as implemented.
> Source: `com.example.dairypos.data.repository.accounting`

---

## Architecture Overview

```
ERP Event (Purchase / Sale / Expense / Production)
    → AccountingTransactionInput
    → JournalRepository.insertAccountingTransactionAndPostJournal()
    → logs row to accountingTransaction table
    → postJournalEntries()
    → looks up accountingJournalsMap by transactionType + subType
    → inserts Dr/Cr pairs into journalEntries (ordered by sequence)
```

---

## Repository Classes

### AccountRepository.kt

Manages the Chart of Accounts and supporting reference data.

| Method | Description |
|---|---|
| `getAllAccounts()` | All COA entries joined with account type name, ordered by code |
| `getAccountById(id)` | Single COA entry |
| `saveAccount(json)` | Insert or update a COA entry (code, name, accountTypeId) |
| `deleteAccount(id)` | Delete a COA entry |
| `getAccountsDropDown()` | Lightweight id/code/name list for UI dropdowns |
| `getPostingAccounts()` | Only accounts where `isPosting = 1`, returned as `List<ChartAccount>` |
| `getAccountIdByCode(code)` | Scalar lookup — returns int id for a given COA code |
| `getAllAccountTypes()` | All `standardAccountTypes` rows |
| `saveAccountType(json)` | Insert or update an account type |
| `deleteAccountType(id)` | Delete an account type |
| `getAccountTypeById(id)` | Single account type |
| `getTransactionTypes()` | All rows from `transactionTypes` table |
| `getTxnTypeAccountMapping(typeId)` | Fetches the debitAccountId / creditAccountId mapping from `accountingJournalsMap` for a given type |

---

### JournalRepository.kt

Core journal engine — posting pipeline, inventory movements, and status utilities.

| Method | Description |
|---|---|
| `insertAccountingTransactionAndPostJournal(input)` | Main entry point: logs to `accountingTransaction`, then calls `postJournalEntries()` |
| `postJournalEntries(transactionId)` | Reads `accountingJournalsMap` rules for the transaction type+subType, inserts a `journalEntries` row per sequence |
| `getJournalEntries()` | All journal entries with debit/credit account names |
| `saveJournalEntry(json)` | Manual upsert to `journalEntries` (matches on referenceType + referenceId) |
| `insertTransaction(...)` | Logs a product quantity movement to the `transactions` table (stock in/out) |
| `getStatusId(tableKey, statusName)` | Resolves a status name to its integer ID via `statusEnumTableMap` |
| `setStatus(tableName, id, statusName)` | Updates `statusId` on any status-bearing table row |
| `recordJournalEntry(...)` | **Empty stub — not yet implemented** |

**Amount resolution logic (in `DatabaseHelper.calculateAmount`):**
1. Use `txn.amount` if it is > 0
2. Fall back to average cost × quantity for COGS entries (account code `1015`)

**Description format:** `"Auto: {transactionType} | {table}#{refId}"`

**subType matching:** If the `AccountingTransactionInput` carries a `subType`, `postJournalEntries` matches rules where `tta.subType = subType OR tta.subType IS NULL`. If subType is null, only null-subType rules fire.

---

### FinancialReportRepository.kt

All read-only reporting queries. No writes.

| Method | Date Params | Description |
|---|---|---|
| `getProfitAndLoss(from, to)` | date range | Aggregates Revenue and Expense accounts from `journalEntries`; returns net per account |
| `getJournalEntryReport(from, to)` | date range | Detailed listing of all journal entries with account names and type badges |
| `getTransactionReport(from, to)` | date range | Inventory movement log from `transactions` table (in/out per product) |
| `getTrialBalance()` | none (all-time) | Total debits and total credits per account across all journal entries |
| `getBalanceSheet(asOfDate)` | single date | Asset, Liability, Equity account balances up to and including the given date |
| `getIncomeStatement(from, to)` | date range | Net income/expense per account for the period |
| `getCashFlow(from, to)` | date range | Inflow/Outflow grouped by date for account code `1000` (Cash) only |

---

## Transaction Types That Currently Auto-Post

These ERP events call `insertAccountingTransactionAndPostJournal()` and produce journal entries:

| Source Repository | Transaction Type | What It Posts |
|---|---|---|
| `ProcurementRepository` | `Purchase` | seq 1: Dr. Inventory / Cr. Payables; seq 2 (temp): Dr. Payables / Cr. Cash |
| `SalesRepository` | `Sale` | Sale amount + unit price |
| `ProductionRepository` | `Mix` | Mix production expense |
| `ProductionRepository` | `ProductionExpense` | Reversal of expenses into production |
| `ExpenseRepository` | `LaborExpense` (×2 methods) | Daily labor wages; direct labor expense save |
| `ExpenseRepository` | `RentalExpense` | Daily rental cost |
| `ExpenseRepository` | `FuelExpense` | Fuel expense save |

---

## Core Tables

| Table | Purpose |
|---|---|
| `chartAccounts` | Chart of Accounts — every ledger account |
| `standardAccountTypes` | Account type taxonomy (Asset, Liability, Equity, Income/Revenue, Expense) |
| `transactionTypes` | Named event types that drive journal automation |
| `accountingJournalsMap` | Maps transactionTypeId + optional subType → debitAccountId / creditAccountId / sequence / condition |
| `accountingTransaction` | Source log for every ERP event before journal posting |
| `journalEntries` | Final double-entry ledger (Dr/Cr pairs) |
| `transactions` | Inventory quantity movements (stock in/out log) |

---

## Known Issues (Must Fix Before Release)

- `TransactionTypes` INSERT has a syntax error: `('ProductionExpense'),0` — stray `0` must be removed
- COA codes `7000` and `7001` are both named "Vet Expense" — duplicate, needs resolution
- `PayablePayment` TransactionType is defined but not implemented — Purchase seq 2 is a temporary cash-assumed workaround
- `Invoice` TransactionType is defined but has no mapping in `accountingJournalsMap`
- `recordJournalEntry()` in `JournalRepository` is an empty stub — dead code currently
- P&L query uses `ca.id IN (j.debitAccountId, j.creditAccountId)` — the JOIN can produce duplicate rows per journal entry; may affect totals
- P&L report filters `'Revenue','Expense'` but Income Statement filters `'Income','Expense'` — account type naming is inconsistent across the two reports
- Cash Flow is hardcoded to account code `1000` — reports only one cash account, no categorization
