# AccountingLogic.md
> As-built accounting architecture reference for DairyPOS.

---

## Journal Entry Pipeline

```
Operational Event (sale, expense, production, payment…)
    → AccountingTransactionInput (type, subType, table, refId, amount, date)
    → helper.insertAccountingTransactionAndPostJournal()
        → inserts row into accountingTransaction
        → JournalRepository.postJournalEntries(transactionId)
            → looks up AccountingJournalsMap by transactionType + subType (with null fallback)
            → for each matching rule (ordered by sequence):
                → calculateAmount(txn, creditAccId)
                → if amount <= 0 → skip (no zero-amount entries ever post)
                → inserts Dr/Cr pair into journalEntries
```

---

## Chart of Accounts (COA)

| Code | Name                         | Type      |
|------|------------------------------|-----------|
| 1000 | Cash                         | Asset     |
| 1001 | Inventory                    | Asset     |
| 1002 | Accounts Receivable          | Asset     |
| 1003 | Bank                         | Asset     |
| 1010 | Inventory: Purchased Milk    | Asset     |
| 1012 | WIP Inventory                | Asset     |
| 1015 | Inventory: Production WIP    | Asset     |
| 2000 | Payables (AP — Suppliers)    | Liability |
| 2001 | Accrued Rent Payable         | Liability |
| 2002 | Labor Payable                | Liability |
| 2003 | Accrued Electricity Payable  | Liability |
| 2004 | Accrued Fuel Payable         | Liability |
| 4000 | Milk Sales                   | Income    |
| 5000 | Feed Expense                 | Expense   |
| 5001 | COGS                         | Expense   |
| 5002 | COGS: Purchased Milk         | Expense   |
| 5003 | COGS: Produced Milk          | Expense   |
| 5004 | Production Overhead          | Expense   |
| 6000 | Labor Expense                | Expense   |
| 6001 | Rent Expense                 | Expense   |
| 6002 | Utility Expense              | Expense   |
| 7000 | Electricity Expense          | Expense   |
| 7001 | Vet Expense                  | Expense   |
| 8000 | Fuel Expense                 | Expense   |

---

## TransactionTypes and Journal Mappings

### Purchase
| Seq | subType    | Dr   | Cr   |
|-----|------------|------|------|
| 1   | Consumable | 1001 | 2000 |
| 1   | Raw        | 1010 | 2000 |

### PayablePayment (AP — Supplier)
| Seq | subType | Dr   | Cr   |
|-----|---------|------|------|
| 1   | Cash    | 2000 | 1000 |
| 1   | Bank    | 2000 | 1003 |

### FeedUse
| Seq | subType | Dr   | Cr   |
|-----|---------|------|------|
| 1   | NULL    | 1015 | 1001 |

### Mix
| Seq | subType | Dr   | Cr   |
|-----|---------|------|------|
| 1   | NULL    | 1010 | 1015 |

### Sale
| Seq | subType | Dr   | Cr   |
|-----|---------|------|------|
| 1   | Product | 1002 | 4000 |

### BatchSoldCogs (deferred — not yet wired)
| Seq | subType | Dr   | Cr   |
|-----|---------|------|------|
| 2   | NULL    | 5001 | 1010 |

### ReceivePayment
| Seq | subType | Dr   | Cr   |
|-----|---------|------|------|
| 1   | NULL    | 1000 | 1002 |

### Expense (daily operational accrual)
| Seq | subType     | Dr   | Cr   |
|-----|-------------|------|------|
| 1   | Wages       | 6000 | 2002 |
| 1   | Rent        | 6001 | 2001 |
| 1   | Electricity | 7000 | 2003 |
| 1   | Fuel        | 8000 | 2004 |

### ProductionExpense (WIP capitalization)
| Seq | subType     | Dr   | Cr   |
|-----|-------------|------|------|
| 12  | Wages       | 1015 | 6000 |
| 13  | Fuel        | 1015 | 8000 |
| 14  | Electricity | 1015 | 7000 |
| 15  | Rent        | 1015 | 6001 |
| 15  | Vet         | 1015 | 7001 |

### PayableSettlement (operational payable settlement)
| Seq | subType     | Dr   | Cr   |
|-----|-------------|------|------|
| 1   | Wages       | 2002 | 1000 |
| 1   | Rent        | 2001 | 1000 |
| 1   | Electricity | 2003 | 1000 |
| 1   | Fuel        | 2004 | 1000 |

---

## Operational Expense Accrual Flow

Triggered every time `saveMilkProduction()` is called (once per day):

```
1. stageMonthlyOperationalCosts()
   - For each operationalEntity with a moduleId:
     - If no monthlyOperationalCosts row exists for current period:
       SUM(monetaryCol) from the entity's source table → insert into monthlyOperationalCosts

2. processOperationalCostExpenses()
   - For each entity staged for current month:
     - If no dailyOperationalExpenses row exists for today:
       dailyAmt = monthlyCost / daysInMonth
       Post Expense/subType journal (Dr expense acct, Cr payable acct)
       Insert into dailyOperationalExpenses

3. wipCapitalization()  ← also called from saveMilkProduction()
   - Reads all ProductionExpense rules from accountingJournalsMap
   - For each New journal entry matching those rules:
     Post ProductionExpense/subType (Dr 1015 WIP, Cr expense acct)
     Mark source entries as 'Expensed'
```

---

## AP Flow (Supplier Payables — account 2000)

```
savePurchase()       → Purchase/Raw or Purchase/Consumable → Dr 1010 or 1001, Cr 2000
savePayablePayment() → PayablePayment/Cash or /Bank → Dr 2000, Cr 1000 or 1003
```

---

## Sales Flow

```
recordSale()
  → Sale/Product  → Dr 1002 AR, Cr 4000 Milk Sales  (refId = sales.id)
  → BatchSoldCogs (deferred — not wired)
```

Customer rate: first tries `customers.rate`; if 0/null, falls back to `SellableProductRates` where `products.name = 'Milk'` (default 240.0).

---

## PayableSettlement Flow (Operational Payables)

Screen: `pay_operational_liabilities` (Expenses → Pay Liabilities)

```
User selects payable type (Wages/Rent/Electricity/Fuel)
  → saveOperationalPayment(subType, amount, date)
  → PayableSettlement/subType
  → Dr payable acct (2001/2002/2003/2004), Cr 1000 Cash
```

Balances queried from `journalEntries` net credit–debit for codes 2001–2004.

---

## Modules Registry

| Module      | TransactionTypes (owns journal mapping)                          |
|-------------|------------------------------------------------------------------|
| Procurement | Purchase, PayablePayment                                         |
| Sales       | Sale, BatchSoldCogs, ReceivePayment                              |
| Production  | Production, Mix, FeedUse, ProductionExpense                      |
| Expenses    | Expense, PayableSettlement                                       |

Note: `Invoice` TransactionType has `moduleId = NULL` (document-only; no journal mapping).

---

## Known Deferred Items

- **BatchSoldCogs**: journal rule exists but not triggered — COGS deferred until batch settlement logic is built.
- **Bug: null-subType match**: if `accountingJournalsMap` has a NULL subType row for a type that also has subType rows, the null row may match unexpectedly. Review before adding mixed-subType rules.
- **Invoice**: TransactionType is document-only. No `accountingJournalsMap` entry; `moduleId` is NULL. Configure via Modules Setup screen if journal entries are ever needed.
- **Production/NULL map entry**: deleted in DB_VERSION 4 migration (was incorrect).
- **ProductionExpense/Feed-not-used**: deleted in DB_VERSION 4 migration (not evidence-based).
