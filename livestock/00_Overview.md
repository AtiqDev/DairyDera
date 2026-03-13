# Livestock Module — Master Architecture Reference

> **Session startup:** Read `CLAUDE.md` first, then this file. This document is the
> single source of truth for all livestock DB schema, COA accounts, TransactionTypes,
> and architectural decisions. Every other dossier (`01_` through `05_`) depends on it.

---

## Context

DairyPOS is adding a Livestock Management domain. Animals are tracked **individually by tag
number**. The full scope includes:
- Herd registry (individual animals + groups/pens)
- Animal lifecycle transactions (purchase, birth, sale, death) with balance-sheet asset accounting
- Full health & veterinary scheduling (vaccination templates, per-animal treatment history)
- Full reproduction cycle (heat → AI/natural insemination → pregnancy check → calving)
- Lactation cycle tracking bridging reproduction events to production

---

## DB_VERSION

| Version | Change |
|---------|--------|
| 1 | Initial schema |
| 2 | modulesRegistry + transactionTypes.moduleId |
| 3 | (check DatabaseHelper.kt for what was added) |
| 4 | (check DatabaseHelper.kt for what was added) |
| **5** | **All livestock tables + COA + TransactionTypes + modulesRegistry row** |

**In `DatabaseHelper.kt`:** change `DB_VERSION = 4` → `DB_VERSION = 5`

---

## New Table Constants (add to `DatabaseHelper` companion object)

```kotlin
internal const val T_ANIMAL_GROUPS         = "animalGroups"
internal const val T_ANIMALS               = "animals"
internal const val T_ANIMAL_TRANSACTIONS   = "animalTransactions"
internal const val T_ANIMAL_HEALTH_EVENTS  = "animalHealthEvents"
internal const val T_VACCINATION_SCHEDULES = "vaccinationSchedules"
internal const val T_ANIMAL_REPRODUCTION   = "animalReproduction"
internal const val T_ANIMAL_LACTATION      = "animalLactation"
```

---

## New Lazy Repository Properties (add to `DatabaseHelper` class body)

```kotlin
val livestock         by lazy { LiveStockRepository(this) }
val animalTxn         by lazy { AnimalTransactionRepository(this) }
val animalHealth      by lazy { AnimalHealthRepository(this) }
val animalRepro       by lazy { AnimalReproductionRepository(this) }
val animalLactation   by lazy { AnimalLactationRepository(this) }
```

---

## New modulesRegistry Row

The existing `modulesRegistry` has rows 1–4 (Procurement, Sales, Production, Expenses).
Livestock is row 5.

```sql
INSERT INTO modulesRegistry (id, name, description)
VALUES (5, 'Livestock', 'Herd management, animal lifecycle, health and reproduction');
```

---

## New COA Accounts

Insert into `chartAccounts`. The `standardAccountTypes` FK values: look up
`standardAccountTypes.id` where `typeName = 'Asset'` / `'Income'` / `'Expense'`.
In practice use `getAccountTypeId('Asset')` helper already on `DatabaseHelper`.

```sql
-- Check DatabaseHelper.getAccountTypeId() for how to resolve the typeId FK
INSERT INTO chartAccounts (code, name, accountTypeId)
  SELECT '1500', 'Livestock Assets', id FROM standardAccountTypes WHERE typeName='Asset';

INSERT INTO chartAccounts (code, name, accountTypeId)
  SELECT '1501', 'Livestock Gain on Sale', id FROM standardAccountTypes WHERE typeName='Income';

INSERT INTO chartAccounts (code, name, accountTypeId)
  SELECT '1502', 'Livestock Loss (Death/Cull)', id FROM standardAccountTypes WHERE typeName='Expense';
```

---

## New TransactionTypes (moduleId = 5)

```sql
INSERT INTO transactionTypes (name, moduleId) VALUES ('AnimalPurchase', 5);
INSERT INTO transactionTypes (name, moduleId) VALUES ('AnimalSale',     5);
INSERT INTO transactionTypes (name, moduleId) VALUES ('AnimalDeath',    5);
```

---

## AccountingJournalsMap Entries

Must be configured via the **Modules Setup UI** (`modules_registry` screen, s0) after
DB_VERSION 5 is live, OR inserted directly. Expected mappings:

| TransactionType | subType | Debit Account | Credit Account |
|-----------------|---------|---------------|----------------|
| AnimalPurchase  | (null/any) | 1500 Livestock Assets | Cash or AP account |
| AnimalSale      | (null/any) | Cash or AR account | 1500 Livestock Assets |
| AnimalSale gain | gain | 1500 (net) | 1501 Livestock Gain on Sale |
| AnimalDeath     | (null/any) | 1502 Livestock Loss | 1500 Livestock Assets |

> Note: For AnimalPurchase the credit side varies (Cash vs AP). Use a general payable
> account (e.g., 2000 Accounts Payable) as the default. The user can edit via Modules Setup.

---

## Full DB Schema (all 7 new tables)

### `animalGroups`
```sql
CREATE TABLE IF NOT EXISTS animalGroups (
    id        INTEGER PRIMARY KEY AUTOINCREMENT,
    name      TEXT NOT NULL,
    notes     TEXT,
    createdAt TEXT DEFAULT (datetime('now'))
);
```

### `animals`
```sql
CREATE TABLE IF NOT EXISTS animals (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    tagNumber     TEXT NOT NULL UNIQUE,
    name          TEXT,
    breed         TEXT,
    gender        TEXT NOT NULL CHECK(gender IN ('F','M','C')),
    dateOfBirth   TEXT,
    damId         INTEGER REFERENCES animals(id),
    sireInfo      TEXT,
    groupId       INTEGER REFERENCES animalGroups(id),
    status        TEXT DEFAULT 'active'
                       CHECK(status IN ('active','dry','sick','pregnant','sold','dead')),
    purchaseDate  TEXT,
    purchasePrice REAL,
    bookValue     REAL,
    notes         TEXT,
    createdAt     TEXT DEFAULT (datetime('now'))
);
```

### `animalTransactions`
```sql
CREATE TABLE IF NOT EXISTS animalTransactions (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    animalId         INTEGER NOT NULL REFERENCES animals(id),
    txnType          TEXT NOT NULL
                          CHECK(txnType IN ('purchase','birth','sale','death','cull','transfer')),
    date             TEXT NOT NULL,
    amount           REAL,
    counterpartyName TEXT,
    accountingTxnId  INTEGER REFERENCES accountingTransaction(id),
    notes            TEXT,
    createdAt        TEXT DEFAULT (datetime('now'))
);
```

### `animalHealthEvents`
```sql
CREATE TABLE IF NOT EXISTS animalHealthEvents (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    animalId    INTEGER NOT NULL REFERENCES animals(id),
    eventType   TEXT NOT NULL
                     CHECK(eventType IN ('vaccination','treatment','diagnosis','checkup','other')),
    date        TEXT NOT NULL,
    description TEXT NOT NULL,
    medication  TEXT,
    dosage      TEXT,
    vetName     TEXT,
    cost        REAL,
    expenseId   INTEGER REFERENCES expenses(id),
    scheduleId  INTEGER REFERENCES vaccinationSchedules(id),
    nextDueDate TEXT,
    notes       TEXT,
    createdAt   TEXT DEFAULT (datetime('now'))
);
```

### `vaccinationSchedules`
```sql
CREATE TABLE IF NOT EXISTS vaccinationSchedules (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    name         TEXT NOT NULL,
    intervalDays INTEGER,
    notes        TEXT,
    createdAt    TEXT DEFAULT (datetime('now'))
);
```

### `animalReproduction`
```sql
CREATE TABLE IF NOT EXISTS animalReproduction (
    id                   INTEGER PRIMARY KEY AUTOINCREMENT,
    animalId             INTEGER NOT NULL REFERENCES animals(id),
    cycleNumber          INTEGER,
    heatDate             TEXT,
    inseminationDate     TEXT,
    inseminationType     TEXT CHECK(inseminationType IN ('AI','natural')),
    bullInfo             TEXT,
    pregnancyCheckDate   TEXT,
    pregnancyConfirmed   INTEGER,
    expectedCalvingDate  TEXT,
    actualCalvingDate    TEXT,
    calfId               INTEGER REFERENCES animals(id),
    calfGender           TEXT,
    outcome              TEXT DEFAULT 'pending'
                              CHECK(outcome IN ('live','stillbirth','abortion','pending')),
    notes                TEXT,
    createdAt            TEXT DEFAULT (datetime('now'))
);
```

### `animalLactation`
```sql
CREATE TABLE IF NOT EXISTS animalLactation (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    animalId         INTEGER NOT NULL REFERENCES animals(id),
    lactationNumber  INTEGER,
    startDate        TEXT,
    dryOffDate       TEXT,
    status           TEXT DEFAULT 'active'
                          CHECK(status IN ('active','dry','complete')),
    notes            TEXT,
    createdAt        TEXT DEFAULT (datetime('now'))
);
```

---

## onUpgrade Block (case 4 → 5)

Add inside `onUpgrade()` in `DatabaseHelper.kt`, following the existing pattern:

```kotlin
if (oldVersion < 5) {
    // --- Livestock tables ---
    db.execSQL("""
        CREATE TABLE IF NOT EXISTS animalGroups (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL,
            notes TEXT,
            createdAt TEXT DEFAULT (datetime('now'))
        )
    """.trimIndent())

    db.execSQL("""
        CREATE TABLE IF NOT EXISTS animals (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            tagNumber TEXT NOT NULL UNIQUE,
            name TEXT,
            breed TEXT,
            gender TEXT NOT NULL CHECK(gender IN ('F','M','C')),
            dateOfBirth TEXT,
            damId INTEGER REFERENCES animals(id),
            sireInfo TEXT,
            groupId INTEGER REFERENCES animalGroups(id),
            status TEXT DEFAULT 'active'
                        CHECK(status IN ('active','dry','sick','pregnant','sold','dead')),
            purchaseDate TEXT,
            purchasePrice REAL,
            bookValue REAL,
            notes TEXT,
            createdAt TEXT DEFAULT (datetime('now'))
        )
    """.trimIndent())

    db.execSQL("""
        CREATE TABLE IF NOT EXISTS animalTransactions (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            animalId INTEGER NOT NULL REFERENCES animals(id),
            txnType TEXT NOT NULL
                CHECK(txnType IN ('purchase','birth','sale','death','cull','transfer')),
            date TEXT NOT NULL,
            amount REAL,
            counterpartyName TEXT,
            accountingTxnId INTEGER REFERENCES accountingTransaction(id),
            notes TEXT,
            createdAt TEXT DEFAULT (datetime('now'))
        )
    """.trimIndent())

    db.execSQL("""
        CREATE TABLE IF NOT EXISTS vaccinationSchedules (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL,
            intervalDays INTEGER,
            notes TEXT,
            createdAt TEXT DEFAULT (datetime('now'))
        )
    """.trimIndent())

    db.execSQL("""
        CREATE TABLE IF NOT EXISTS animalHealthEvents (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            animalId INTEGER NOT NULL REFERENCES animals(id),
            eventType TEXT NOT NULL
                CHECK(eventType IN ('vaccination','treatment','diagnosis','checkup','other')),
            date TEXT NOT NULL,
            description TEXT NOT NULL,
            medication TEXT,
            dosage TEXT,
            vetName TEXT,
            cost REAL,
            expenseId INTEGER REFERENCES expenses(id),
            scheduleId INTEGER REFERENCES vaccinationSchedules(id),
            nextDueDate TEXT,
            notes TEXT,
            createdAt TEXT DEFAULT (datetime('now'))
        )
    """.trimIndent())

    db.execSQL("""
        CREATE TABLE IF NOT EXISTS animalReproduction (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            animalId INTEGER NOT NULL REFERENCES animals(id),
            cycleNumber INTEGER,
            heatDate TEXT,
            inseminationDate TEXT,
            inseminationType TEXT CHECK(inseminationType IN ('AI','natural')),
            bullInfo TEXT,
            pregnancyCheckDate TEXT,
            pregnancyConfirmed INTEGER,
            expectedCalvingDate TEXT,
            actualCalvingDate TEXT,
            calfId INTEGER REFERENCES animals(id),
            calfGender TEXT,
            outcome TEXT DEFAULT 'pending'
                CHECK(outcome IN ('live','stillbirth','abortion','pending')),
            notes TEXT,
            createdAt TEXT DEFAULT (datetime('now'))
        )
    """.trimIndent())

    db.execSQL("""
        CREATE TABLE IF NOT EXISTS animalLactation (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            animalId INTEGER NOT NULL REFERENCES animals(id),
            lactationNumber INTEGER,
            startDate TEXT,
            dryOffDate TEXT,
            status TEXT DEFAULT 'active'
                CHECK(status IN ('active','dry','complete')),
            notes TEXT,
            createdAt TEXT DEFAULT (datetime('now'))
        )
    """.trimIndent())

    // --- modulesRegistry row ---
    db.execSQL("INSERT OR IGNORE INTO modulesRegistry (id, name, description) VALUES (5, 'Livestock', 'Herd management, animal lifecycle, health and reproduction')")

    // --- COA accounts ---
    db.execSQL("INSERT OR IGNORE INTO chartAccounts (code, name, accountTypeId) SELECT '1500', 'Livestock Assets', id FROM standardAccountTypes WHERE typeName='Asset'")
    db.execSQL("INSERT OR IGNORE INTO chartAccounts (code, name, accountTypeId) SELECT '1501', 'Livestock Gain on Sale', id FROM standardAccountTypes WHERE typeName='Income'")
    db.execSQL("INSERT OR IGNORE INTO chartAccounts (code, name, accountTypeId) SELECT '1502', 'Livestock Loss (Death/Cull)', id FROM standardAccountTypes WHERE typeName='Expense'")

    // --- TransactionTypes ---
    db.execSQL("INSERT OR IGNORE INTO transactionTypes (name, moduleId) VALUES ('AnimalPurchase', 5)")
    db.execSQL("INSERT OR IGNORE INTO transactionTypes (name, moduleId) VALUES ('AnimalSale', 5)")
    db.execSQL("INSERT OR IGNORE INTO transactionTypes (name, moduleId) VALUES ('AnimalDeath', 5)")
}
```

---

## Critical Files (read before starting any livestock session)

| File | Purpose |
|------|---------|
| `CLAUDE.md` | Project conventions, repo pattern, extension function rules |
| `app/src/main/java/com/example/dairypos/DatabaseHelper.kt` | DB version, onUpgrade, table constants, helpers |
| `app/src/main/java/com/example/dairypos/MainActivity.kt` | WebView bridge dispatcher — where new `when` branches go |
| `app/src/main/assets/app.js` | bundledScreens registry |
| `app/src/main/assets/index.html` | Script tag list |
| `app/src/main/java/com/example/dairypos/data/repository/erp/ExpenseRepository.kt` | Reference repo pattern |
| `app/src/main/assets/s9_pay_operational_liabilities.js` | Reference screen JS pattern |
| `app/src/main/assets/templates/daily_stub.html` | Reference stub tile pattern |

---

## Execution Order

| Step | Dossier | What it builds |
|------|---------|----------------|
| 1 | `00_Overview` (this file) | DB_VERSION 5 migration in DatabaseHelper.kt |
| 2 | `01_HerdRegistry` | Animal CRUD, groups, livestock stub navigation |
| 3 | `02_AnimalTransactions` | Lifecycle events + accounting |
| 4 | `03_HealthVet` | Health events + vaccination schedules |
| 5 | `04_Reproduction` | Breeding cycle, calving |
| 6 | `05_ProductionLinkage` | Lactation cycles |

**Important:** The DB migration (this file) must be implemented first. All other modules
depend on the tables existing. Each module can then be built in a fresh session by reading
only CLAUDE.md + its own dossier + the critical files listed above.
