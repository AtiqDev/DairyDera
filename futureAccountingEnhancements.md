# DairyPOS — Future Accounting Enhancements

> Backlog of planned and identified improvements to the accounting system.
> Based on code study of `data.repository.accounting` and `CLAUDE.md`.
> Items are grouped by theme, not by priority.

---

## 1. AP Payment Module (High Priority — Blocks Purchase Cleanup)

**Current state:** Purchase posting fires two sequences — seq 1 creates the liability (Dr. Inventory / Cr. Payables) and seq 2 immediately settles it (Dr. Payables / Cr. Cash). Seq 2 is a placeholder that assumes cash payment.

**Needed:**
- Build the AP Payment screen and `PayablePaymentRepository`
- Add `PayablePayment` transaction type to `accountingJournalsMap` (Dr. Payables / Cr. Cash)
- Remove seq 2 from the Purchase mapping entirely

**Files affected:** `accountingJournalsMap` seed data, new `PayablePaymentRepository`, `DatabaseHelper` (add delegating method)

---

## 2. Invoice → Journal Mapping

**Current state:** `Invoice` exists in `transactionTypes` but has no rows in `accountingJournalsMap`. No journal entries fire when an invoice is saved.

**Needed:**
- Decide the correct Dr/Cr pair for invoice creation (e.g., Dr. Accounts Receivable / Cr. Revenue)
- Add the mapping row
- Confirm `SalesRepository` calls `insertAccountingTransactionAndPostJournal` with type `Invoice` at the right point

---

## 3. Conditional Journal Entries (`condition` column)

**Current state:** `accountingJournalsMap.condition` column exists and is reserved, but `postJournalEntries()` never reads it. All sequences fire unconditionally (except the amount > 0 guard).

**Needed:**
- Parse the `condition` string from the map row
- Evaluate it against the `AccountingTransactionInput` payload (e.g., `cashPaid=true`)
- Skip the sequence if the condition is not met
- This enables evidence-based chaining without hardcoded logic in repositories

---

## 4. `parentJournalId` — Audit Trail Chaining

**Current state:** The `parentJournalId` self-referencing FK on `journalEntries` is defined (or planned) but never populated. Reversals and corrections have no formal link to the original entry.

**Needed:**
- Populate `parentJournalId` when posting a reversal or correction entry
- Useful for: ProductionExpense reversals, future void/correction workflows
- Enables full audit trail: original → reversal → re-post chain

---

## 5. Month-End Close / EOM Automation

**Current state:** No period closing exists. Journal entries accumulate indefinitely with no concept of a closed period. A "Close Month" button is planned but not built.

**Needed:**
- "Close Month" action that:
  - Marks all entries up to month-end as locked (no edits)
  - Posts closing entries (Income/Expense → Retained Earnings)
  - Creates an EOM summary record
- Protect closed periods from backdated entries

---

## 6. T_AP_ACCRUAL_HEADS — Accrual Reporting Classifier

**Current state:** `T_AP_ACCRUAL_HEADS` is referenced in `CLAUDE.md` as a static COA classifier for accrual-based reporting. It is in progress but not yet functional.

**Needed:**
- Define and seed the `T_AP_ACCRUAL_HEADS` table
- Link it to relevant expense accounts in the COA
- Use it to generate accrual-based reports separate from cash-basis reports

---

## 7. Cash Flow Report — Categorization

**Current state:** `getCashFlow()` is hardcoded to a single account (code `1000`). It only distinguishes Inflow vs Outflow with no further categorization.

**Needed:**
- Operating / Investing / Financing categories
- Support multiple cash/bank accounts (not just `1000`)
- Link cash movements to their originating transaction type for automatic categorization

---

## 8. Fix Reporting Inconsistencies

### 8a. P&L Double-Count Risk
`getProfitAndLoss()` uses `ca.id IN (j.debitAccountId, j.creditAccountId)` in a JOIN, which can return two rows per journal entry (once as debit side, once as credit side) for accounts that appear on both sides. Verify totals and refactor to use explicit CASE SUM logic (as `getTrialBalance()` does).

### 8b. Account Type Name Alignment
- `getProfitAndLoss()` filters `'Revenue','Expense'`
- `getIncomeStatement()` filters `'Income','Expense'`
These should use the same type names. Align `standardAccountTypes` seed data and all report queries to a single canonical naming convention.

---

## 9. `recordJournalEntry()` — Implement or Remove

**Current state:** `JournalRepository.recordJournalEntry()` has a signature but an empty body. It is never called.

**Decision needed:** Either implement it as a thin wrapper over a direct `journalEntries` INSERT (useful for manual override entries), or delete it entirely.

---

## 10. AR Aging / Customer Statement

**Current state:** No accounts-receivable aging report exists. Invoices are created but there is no query that shows what is outstanding per customer and how old the debt is.

**Needed:**
- AR aging buckets (0–30, 31–60, 61–90, 90+ days)
- Customer statement view: invoices issued, payments received, balance

---

## 11. AP Aging

Symmetric to AR aging — once the AP Payment module exists, an AP aging report shows outstanding payables per supplier by age bucket.

---

## 12. Tax / GST Line Handling

**Current state:** No tax accounts or tax-rate configuration exist. All amounts are recorded gross.

**Future consideration:** If the business becomes GST-registered or requires tax-separated reporting, input/output tax accounts will need to be added to the COA and journal mapping rules updated.

---

## 13. Multiple Cash/Bank Accounts

**Current state:** Cash Flow and settlement entries assume a single Cash account (code `1000`). If the business operates multiple bank accounts or petty cash funds, this breaks.

**Future consideration:** Allow payment entries to specify which cash/bank account to credit, and reflect that in Cash Flow reporting.

---

## Summary of Blocked Items (Cannot Ship Without)

| Item | Blocking What |
|---|---|
| AP Payment module (§1) | Accurate liability tracking; purchase seq 2 removal |
| Invoice mapping (§2) | Revenue recognition in the ledger |
| Known syntax/duplicate bugs (see `accounting.md`) | Data integrity on fresh install |
