# Livestock Module 02 — Animal Transactions

> **Session startup:** Read `CLAUDE.md` + `livestock/00_Overview.md` first, then this file.
> Also read the **Critical Files** section at the bottom before touching any code.

---

## Context

Animal Transactions records every lifecycle event that changes an animal's status or
affects the balance sheet:

| Event | What happens |
|-------|-------------|
| `purchase` | Animal bought — debits **Livestock Assets (1500)** |
| `birth` | Calf born from existing dam — no accounting entry (cost basis = 0) |
| `sale` | Animal sold — credits Livestock Assets, records gain or loss |
| `death` / `cull` | Animal dies — writes off book value to **Livestock Loss (1502)** |
| `transfer` | Animal moves between groups — no accounting entry |

Each event also writes a row to `animalTransactions` for audit trail.

---

## Prerequisites

- `00_Overview.md` DB migration done (DB_VERSION = 5, all livestock tables exist)
- `01_HerdRegistry.md` implemented (animals must be selectable from the UI)

---

## What This Dossier Builds

| Artifact | Location |
|----------|---------|
| `AnimalTransactionRepository.kt` | `data/repository/erp/AnimalTransactionRepository.kt` |
| `s12_animal_transactions.js` | `assets/s12_animal_transactions.js` |
| `templates/animal_transactions.html` | `assets/templates/animal_transactions.html` |
| Edits to `DatabaseHelper.kt` | Add lazy repo (if not already from 01) |
| Edits to `MainActivity.kt` | Add bridge method routing |
| Edits to `app.js` | Add to bundledScreens |
| Edits to `index.html` | Add script tag |

---

## AccountingJournalsMap Setup Required

Before this module works, the Modules Setup screen (`modules_registry`) must have journal
mappings for `AnimalPurchase`, `AnimalSale`, `AnimalDeath`. Configure via UI or insert:

```sql
-- AnimalPurchase: Dr Livestock Assets / Cr Accounts Payable
INSERT INTO accountingJournalsMap (transactionTypeId, subType, sequence, debitAccountId, creditAccountId)
  SELECT tt.id, NULL, 1,
    (SELECT id FROM chartAccounts WHERE code='1500'),
    (SELECT id FROM chartAccounts WHERE code='2000')   -- AP or cash account
  FROM transactionTypes tt WHERE tt.name='AnimalPurchase';

-- AnimalSale: Dr Cash/AR / Cr Livestock Assets
INSERT INTO accountingJournalsMap (transactionTypeId, subType, sequence, debitAccountId, creditAccountId)
  SELECT tt.id, NULL, 1,
    (SELECT id FROM chartAccounts WHERE code='1100'),  -- Cash
    (SELECT id FROM chartAccounts WHERE code='1500')   -- Livestock Assets
  FROM transactionTypes tt WHERE tt.name='AnimalSale';

-- AnimalDeath: Dr Livestock Loss / Cr Livestock Assets
INSERT INTO accountingJournalsMap (transactionTypeId, subType, sequence, debitAccountId, creditAccountId)
  SELECT tt.id, NULL, 1,
    (SELECT id FROM chartAccounts WHERE code='1502'),  -- Livestock Loss
    (SELECT id FROM chartAccounts WHERE code='1500')   -- Livestock Assets
  FROM transactionTypes tt WHERE tt.name='AnimalDeath';
```

> **Note:** Verify COA code for Cash/AR in your actual `chartAccounts` table using the
> Query tool (`s10_query.js`). The codes 1100 and 2000 are assumptions — confirm before
> inserting.

---

## Repository: `AnimalTransactionRepository.kt`

```kotlin
package com.example.dairypos.data.repository.erp

import android.content.ContentValues
import android.util.Log
import com.example.dairypos.DatabaseHelper
import com.example.dairypos.model.AccountingTransactionInput
import org.json.JSONObject

class AnimalTransactionRepository(private val helper: DatabaseHelper) {
    private val db  get() = helper.writableDatabase
    private val rdb get() = helper.readableDatabase

    // ── Purchase ────────────────────────────────────────────────────────────
    // Creates the animal record (if new), then posts accounting entry.
    // Payload JSON: { tagNumber, name, breed, gender, dateOfBirth?, sireInfo?,
    //                 groupId?, purchasePrice, purchaseDate, counterpartyName?, notes? }

    fun saveAnimalPurchase(json: String): String {
        return try {
            val obj    = JSONObject(json)
            val amount = obj.getDouble("purchasePrice")
            val date   = obj.getString("purchaseDate")

            // 1. Upsert the animal record
            val animalCv = ContentValues().apply {
                put("tagNumber",     obj.getString("tagNumber"))
                put("name",          obj.optString("name", ""))
                put("breed",         obj.optString("breed", ""))
                put("gender",        obj.getString("gender"))
                put("dateOfBirth",   obj.optString("dateOfBirth", ""))
                put("sireInfo",      obj.optString("sireInfo", ""))
                put("purchaseDate",  date)
                put("purchasePrice", amount)
                put("bookValue",     amount)
                put("status",        "active")
                put("notes",         obj.optString("notes", ""))
                if (obj.has("groupId") && !obj.isNull("groupId"))
                    put("groupId", obj.getInt("groupId"))
            }
            val animalId = db.insert(DatabaseHelper.T_ANIMALS, null, animalCv)
            if (animalId < 0) return JSONObject().put("error", "Animal insert failed").toString()

            // 2. Post accounting entry
            val input = AccountingTransactionInput(
                type      = "AnimalPurchase",
                subType   = null,
                table     = DatabaseHelper.T_ANIMAL_TRANSACTIONS,
                refId     = animalId.toInt(),
                refId2    = 0,
                productId = 0,
                amount    = amount,
                unitPrice = null,
                date      = date,
                notes     = "Purchase: ${obj.getString("tagNumber")} from ${obj.optString("counterpartyName", "")}"
            )
            val txnId = helper.insertAccountingTransactionAndPostJournal(input)

            // 3. Write animalTransactions audit row
            db.insert(DatabaseHelper.T_ANIMAL_TRANSACTIONS, null, ContentValues().apply {
                put("animalId",         animalId)
                put("txnType",          "purchase")
                put("date",             date)
                put("amount",           amount)
                put("counterpartyName", obj.optString("counterpartyName", ""))
                put("accountingTxnId",  txnId)
                put("notes",            obj.optString("notes", ""))
            })

            JSONObject().put("id", animalId).toString()
        } catch (ex: Exception) {
            Log.e("AnimalTxnRepo", "saveAnimalPurchase failed", ex)
            JSONObject().put("error", ex.message).toString()
        }
    }

    // ── Birth ───────────────────────────────────────────────────────────────
    // Registers a calf born from an existing dam. No accounting (cost basis = 0).
    // Payload: { tagNumber, gender:'C', damId, calfGender?, dateOfBirth, notes? }

    fun recordBirth(json: String): String {
        return try {
            val obj    = JSONObject(json)
            val date   = obj.getString("dateOfBirth")
            val calfCv = ContentValues().apply {
                put("tagNumber",   obj.getString("tagNumber"))
                put("gender",      "C")
                put("dateOfBirth", date)
                put("status",      "active")
                put("bookValue",   0.0)
                put("notes",       obj.optString("notes", ""))
                if (obj.has("damId") && !obj.isNull("damId"))
                    put("damId", obj.getInt("damId"))
                if (obj.has("groupId") && !obj.isNull("groupId"))
                    put("groupId", obj.getInt("groupId"))
            }
            val calfId = db.insert(DatabaseHelper.T_ANIMALS, null, calfCv)

            // Audit row only — no accounting
            db.insert(DatabaseHelper.T_ANIMAL_TRANSACTIONS, null, ContentValues().apply {
                put("animalId", calfId)
                put("txnType",  "birth")
                put("date",     date)
                put("notes",    obj.optString("notes", ""))
            })

            JSONObject().put("id", calfId).toString()
        } catch (ex: Exception) {
            Log.e("AnimalTxnRepo", "recordBirth failed", ex)
            JSONObject().put("error", ex.message).toString()
        }
    }

    // ── Sale ────────────────────────────────────────────────────────────────
    // Payload: { animalId, salePrice, saleDate, counterpartyName?, notes? }

    fun saveAnimalSale(json: String): String {
        return try {
            val obj      = JSONObject(json)
            val animalId = obj.getInt("animalId")
            val amount   = obj.getDouble("salePrice")
            val date     = obj.getString("saleDate")

            // 1. Post accounting entry
            val input = AccountingTransactionInput(
                type      = "AnimalSale",
                subType   = null,
                table     = DatabaseHelper.T_ANIMAL_TRANSACTIONS,
                refId     = animalId,
                refId2    = 0,
                productId = 0,
                amount    = amount,
                unitPrice = null,
                date      = date,
                notes     = "Sale of animal #$animalId to ${obj.optString("counterpartyName", "")}"
            )
            val txnId = helper.insertAccountingTransactionAndPostJournal(input)

            // 2. Update animal status → sold
            db.update(DatabaseHelper.T_ANIMALS,
                ContentValues().apply { put("status", "sold"); put("bookValue", 0.0) },
                "id=?", arrayOf(animalId.toString()))

            // 3. Audit row
            db.insert(DatabaseHelper.T_ANIMAL_TRANSACTIONS, null, ContentValues().apply {
                put("animalId",         animalId)
                put("txnType",          "sale")
                put("date",             date)
                put("amount",           amount)
                put("counterpartyName", obj.optString("counterpartyName", ""))
                put("accountingTxnId",  txnId)
                put("notes",            obj.optString("notes", ""))
            })

            JSONObject().put("id", txnId).toString()
        } catch (ex: Exception) {
            Log.e("AnimalTxnRepo", "saveAnimalSale failed", ex)
            JSONObject().put("error", ex.message).toString()
        }
    }

    // ── Death / Cull ────────────────────────────────────────────────────────
    // Payload: { animalId, date, txnType:'death'|'cull', notes? }

    fun recordAnimalDeath(json: String): String {
        return try {
            val obj      = JSONObject(json)
            val animalId = obj.getInt("animalId")
            val date     = obj.getString("date")
            val txnType  = obj.optString("txnType", "death")

            // Fetch current book value for write-off amount
            val bookValue = helper.fetchScalarDouble(
                "SELECT COALESCE(bookValue, 0) FROM ${DatabaseHelper.T_ANIMALS} WHERE id=?",
                arrayOf(animalId.toString())
            )

            // Only post accounting if book value > 0
            var txnId = 0L
            if (bookValue > 0.0) {
                val input = AccountingTransactionInput(
                    type      = "AnimalDeath",
                    subType   = null,
                    table     = DatabaseHelper.T_ANIMAL_TRANSACTIONS,
                    refId     = animalId,
                    refId2    = 0,
                    productId = 0,
                    amount    = bookValue,
                    unitPrice = null,
                    date      = date,
                    notes     = "$txnType of animal #$animalId — book value write-off"
                )
                txnId = helper.insertAccountingTransactionAndPostJournal(input).toLong()
            }

            // Update status
            db.update(DatabaseHelper.T_ANIMALS,
                ContentValues().apply { put("status", txnType); put("bookValue", 0.0) },
                "id=?", arrayOf(animalId.toString()))

            // Audit row
            db.insert(DatabaseHelper.T_ANIMAL_TRANSACTIONS, null, ContentValues().apply {
                put("animalId",        animalId)
                put("txnType",         txnType)
                put("date",            date)
                put("amount",          bookValue)
                put("accountingTxnId", txnId)
                put("notes",           obj.optString("notes", ""))
            })

            JSONObject().put("status", "ok").toString()
        } catch (ex: Exception) {
            Log.e("AnimalTxnRepo", "recordAnimalDeath failed", ex)
            JSONObject().put("error", ex.message).toString()
        }
    }

    // ── History ─────────────────────────────────────────────────────────────

    fun getTransactionHistory(animalId: Int): String {
        val sql = """
            SELECT at.id, at.txnType, at.date, at.amount, at.counterpartyName, at.notes,
                   a.tagNumber
              FROM ${DatabaseHelper.T_ANIMAL_TRANSACTIONS} at
              JOIN ${DatabaseHelper.T_ANIMALS} a ON a.id = at.animalId
             WHERE at.animalId = ?
             ORDER BY at.date DESC
        """.trimIndent()
        return helper.fetchAll(rdb.rawQuery(sql, arrayOf(animalId.toString()))).toString()
    }

    fun getRecentTransactions(limit: Int = 20): String {
        val sql = """
            SELECT at.id, at.txnType, at.date, at.amount, at.counterpartyName,
                   a.tagNumber, a.name
              FROM ${DatabaseHelper.T_ANIMAL_TRANSACTIONS} at
              JOIN ${DatabaseHelper.T_ANIMALS} a ON a.id = at.animalId
             ORDER BY at.createdAt DESC
             LIMIT ?
        """.trimIndent()
        return helper.fetchAll(rdb.rawQuery(sql, arrayOf(limit.toString()))).toString()
    }
}
```

---

## DatabaseHelper Changes

Add lazy repo (in class body, if not already added):
```kotlin
val animalTxn by lazy { AnimalTransactionRepository(this) }
```

---

## MainActivity Changes

Add bridge routing:
```kotlin
"saveAnimalPurchase"  -> helper.animalTxn.saveAnimalPurchase(payload)
"recordBirth"         -> helper.animalTxn.recordBirth(payload)
"saveAnimalSale"      -> helper.animalTxn.saveAnimalSale(payload)
"recordAnimalDeath"   -> helper.animalTxn.recordAnimalDeath(payload)
"getTransactionHistory" -> {
    val id = JSONObject(payload).getInt("animalId")
    helper.animalTxn.getTransactionHistory(id)
}
"getRecentTransactions" -> helper.animalTxn.getRecentTransactions()
```

---

## Frontend: `s12_animal_transactions.js`

**Screen ID:** `animal_transactions`
**Template:** `templates/animal_transactions.html`
**Navigation:** From `livestock_stub` → Transactions tile. Back → `livestock_stub`.

**UI Flow:**
- Top tabs: **Purchase** | **Birth** | **Sale/Death** | **History**
- Purchase tab: form to buy an animal (tag, breed, gender, price, date, vendor)
- Birth tab: select dam from animal list → enter calf tag + DOB
- Sale/Death tab: search animal by tag → enter event type + amount + date
- History tab: recent transactions list (last 20)

```javascript
window.screenMap['animal_transactions'] = {
  template: 'animal_transactions.html',
  script: {

    DataBridge: {
      async call(fn, payload = {}) {
        try {
          const raw = await nativeApi.call(fn, payload);
          return raw ? JSON.parse(raw) : null;
        } catch (e) { console.error(`[AnimalTxn] ${fn}`, e); return null; }
      },
      getAnimals()             { return this.call('getAnimals', ''); },
      getGroups()              { return this.call('getGroups'); },
      saveAnimalPurchase(obj)  { return this.call('saveAnimalPurchase', obj); },
      recordBirth(obj)         { return this.call('recordBirth', obj); },
      saveAnimalSale(obj)      { return this.call('saveAnimalSale', obj); },
      recordAnimalDeath(obj)   { return this.call('recordAnimalDeath', obj); },
      getRecentTransactions()  { return this.call('getRecentTransactions', ''); },
    },

    TxnApp: class {
      constructor() { this.animals = []; this.groups = []; }

      async init(params = {}) {
        const DB = window.screenMap['animal_transactions'].script.DataBridge;
        [this.animals, this.groups] = await Promise.all([DB.getAnimals(), DB.getGroups()]);
        this._bindTabs();
        this._bindPurchaseForm();
        this._bindBirthForm();
        this._bindSaleDeathForm();
        await this._loadHistory();
        // Default tab
        this._switchTab('purchase');
      }

      _switchTab(tab) {
        ['purchase','birth','sale-death','history'].forEach(t => {
          document.getElementById(`tab-${t}`)?.classList.toggle('active', t === tab);
          document.getElementById(`panel-${t}`)?.style && (
            document.getElementById(`panel-${t}`).style.display = t === tab ? 'block' : 'none'
          );
        });
      }

      _bindTabs() {
        ['purchase','birth','sale-death','history'].forEach(t => {
          const btn = document.getElementById(`tab-${t}`);
          if (btn) btn.addEventListener('click', () => this._switchTab(t));
        });
      }

      _buildAnimalOptions() {
        return this.animals.filter(a => !['sold','dead'].includes(a.status))
          .map(a => `<option value="${a.id}">${a.tagNumber}${a.name ? ' — '+a.name : ''}</option>`)
          .join('');
      }

      _bindPurchaseForm() {
        const btn = document.getElementById('atp-save-btn');
        if (!btn) return;
        btn.addEventListener('click', async () => {
          const payload = {
            tagNumber:       document.getElementById('atp-tag').value.trim(),
            name:            document.getElementById('atp-name').value.trim(),
            breed:           document.getElementById('atp-breed').value.trim(),
            gender:          document.getElementById('atp-gender').value,
            dateOfBirth:     document.getElementById('atp-dob').value,
            purchasePrice:   parseFloat(document.getElementById('atp-price').value) || 0,
            purchaseDate:    document.getElementById('atp-date').value,
            counterpartyName: document.getElementById('atp-vendor').value.trim(),
            notes:           document.getElementById('atp-notes').value.trim(),
          };
          if (!payload.tagNumber || !payload.gender || !payload.purchaseDate || payload.purchasePrice <= 0) {
            alert('Tag, gender, date, and price are required.'); return;
          }
          const DB = window.screenMap['animal_transactions'].script.DataBridge;
          const res = await DB.saveAnimalPurchase(payload);
          if (res?.id) {
            alert(`Animal #${res.id} purchased and recorded.`);
            this._clearForm('atp-');
            this.animals = await DB.getAnimals() || [];
          } else {
            alert('Failed: ' + (res?.error || 'Unknown'));
          }
        });
      }

      _bindBirthForm() {
        const damSel = document.getElementById('atb-dam');
        if (damSel) {
          damSel.innerHTML = '<option value="">Select dam…</option>' +
            this.animals.filter(a => a.gender === 'F')
              .map(a => `<option value="${a.id}">${a.tagNumber}${a.name ? ' — '+a.name:''}</option>`)
              .join('');
        }
        const btn = document.getElementById('atb-save-btn');
        if (!btn) return;
        btn.addEventListener('click', async () => {
          const payload = {
            tagNumber:   document.getElementById('atb-tag').value.trim(),
            dateOfBirth: document.getElementById('atb-dob').value,
            damId:       parseInt(document.getElementById('atb-dam').value) || null,
            notes:       document.getElementById('atb-notes').value.trim(),
          };
          if (!payload.tagNumber || !payload.dateOfBirth) {
            alert('Tag and DOB are required.'); return;
          }
          const DB = window.screenMap['animal_transactions'].script.DataBridge;
          const res = await DB.recordBirth(payload);
          if (res?.id) {
            alert(`Calf #${res.id} registered.`);
            this._clearForm('atb-');
          } else {
            alert('Failed: ' + (res?.error || 'Unknown'));
          }
        });
      }

      _bindSaleDeathForm() {
        const animalSel = document.getElementById('atsd-animal');
        if (animalSel) {
          animalSel.innerHTML = '<option value="">Select animal…</option>' + this._buildAnimalOptions();
        }
        const btn = document.getElementById('atsd-save-btn');
        if (!btn) return;
        btn.addEventListener('click', async () => {
          const eventType = document.getElementById('atsd-type').value;
          const animalId  = parseInt(document.getElementById('atsd-animal').value);
          const date      = document.getElementById('atsd-date').value;
          const notes     = document.getElementById('atsd-notes').value.trim();
          if (!animalId || !eventType || !date) {
            alert('Animal, event type, and date are required.'); return;
          }
          const DB = window.screenMap['animal_transactions'].script.DataBridge;
          let res;
          if (eventType === 'sale') {
            const salePrice = parseFloat(document.getElementById('atsd-amount').value) || 0;
            const buyer     = document.getElementById('atsd-counterparty').value.trim();
            if (salePrice <= 0) { alert('Sale price is required.'); return; }
            res = await DB.saveAnimalSale({ animalId, salePrice, saleDate: date,
                                             counterpartyName: buyer, notes });
          } else {
            res = await DB.recordAnimalDeath({ animalId, date, txnType: eventType, notes });
          }
          if (res && !res.error) {
            alert('Event recorded.');
            this._clearForm('atsd-');
            this.animals = await DB.getAnimals() || [];
            animalSel.innerHTML = '<option value="">Select animal…</option>' + this._buildAnimalOptions();
            await this._loadHistory();
          } else {
            alert('Failed: ' + (res?.error || 'Unknown'));
          }
        });
        // Toggle sale-specific fields
        document.getElementById('atsd-type')?.addEventListener('change', (e) => {
          const isSale = e.target.value === 'sale';
          document.getElementById('atsd-sale-fields').style.display = isSale ? 'block' : 'none';
        });
      }

      async _loadHistory() {
        const DB = window.screenMap['animal_transactions'].script.DataBridge;
        const rows = await DB.getRecentTransactions() || [];
        const tbody = document.getElementById('ath-rows');
        if (!tbody) return;
        tbody.innerHTML = rows.map(r => `
          <tr>
            <td>${r.date}</td>
            <td>${r.tagNumber}</td>
            <td><span class="badge bg-secondary">${r.txnType}</span></td>
            <td>${r.amount ? 'Rs '+r.amount.toLocaleString() : '—'}</td>
            <td>${r.counterpartyName || '—'}</td>
          </tr>`).join('') || '<tr><td colspan="5" class="text-muted text-center">No transactions yet.</td></tr>';
      }

      _clearForm(prefix) {
        document.querySelectorAll(`[id^="${prefix}"]`).forEach(el => {
          if (el.type === 'select-one') el.selectedIndex = 0;
          else el.value = '';
        });
      }
    },

    expose() {
      const app = new this.TxnApp();
      window.AnimalTxnApp = app;
      window.init_animal_transactions = (p) => app.init(p);
    }
  }
};
```

---

## Template: `templates/animal_transactions.html`

```html
<div class="screen-topbar bg-light border-bottom d-flex align-items-center px-3 py-2">
    <button class="btn btn-outline-secondary btn-sm btn-icon me-2"
            onclick="navigate('livestock_stub')" style="width:32px;height:32px;padding:0;">
        <i class="fas fa-arrow-circle-left"></i>
    </button>
    <h5 class="mb-0 flex-grow-1 ms-2">Animal Transactions</h5>
    <div class="ConsoleLog"></div>
</div>

<!-- Tabs -->
<ul class="nav nav-tabs px-3 pt-2 bg-white border-bottom">
    <li class="nav-item">
        <button class="nav-link" id="tab-purchase">Purchase</button>
    </li>
    <li class="nav-item">
        <button class="nav-link" id="tab-birth">Birth</button>
    </li>
    <li class="nav-item">
        <button class="nav-link" id="tab-sale-death">Sale / Death</button>
    </li>
    <li class="nav-item">
        <button class="nav-link" id="tab-history">History</button>
    </li>
</ul>

<div style="padding:1rem;padding-bottom:80px;overflow-y:auto;-webkit-overflow-scrolling:touch;">

<!-- Purchase Panel -->
<div id="panel-purchase">
    <div class="card shadow-sm">
        <div class="card-header bg-success text-white fw-bold">Purchase Animal</div>
        <div class="card-body">
            <div class="mb-3"><label class="form-label">Tag Number *</label>
                <input type="text" class="form-control" id="atp-tag"></div>
            <div class="mb-3"><label class="form-label">Name</label>
                <input type="text" class="form-control" id="atp-name"></div>
            <div class="mb-3"><label class="form-label">Breed</label>
                <input type="text" class="form-control" id="atp-breed"></div>
            <div class="mb-3"><label class="form-label">Gender *</label>
                <select class="form-select" id="atp-gender">
                    <option value="">Select…</option>
                    <option value="F">Female</option>
                    <option value="M">Male</option>
                    <option value="C">Calf</option>
                </select></div>
            <div class="mb-3"><label class="form-label">Date of Birth</label>
                <input type="date" class="form-control" id="atp-dob"></div>
            <div class="mb-3"><label class="form-label">Purchase Price (Rs) *</label>
                <input type="number" class="form-control" id="atp-price" step="0.01" min="0"></div>
            <div class="mb-3"><label class="form-label">Purchase Date *</label>
                <input type="date" class="form-control" id="atp-date"></div>
            <div class="mb-3"><label class="form-label">Vendor / Seller</label>
                <input type="text" class="form-control" id="atp-vendor"></div>
            <div class="mb-3"><label class="form-label">Notes</label>
                <textarea class="form-control" id="atp-notes" rows="2"></textarea></div>
        </div>
        <div class="card-footer">
            <button class="btn btn-success w-100" id="atp-save-btn">
                <i class="fas fa-cow"></i> Record Purchase
            </button>
        </div>
    </div>
</div>

<!-- Birth Panel -->
<div id="panel-birth" style="display:none;">
    <div class="card shadow-sm">
        <div class="card-header bg-info text-dark fw-bold">Register Birth</div>
        <div class="card-body">
            <div class="mb-3"><label class="form-label">Calf Tag Number *</label>
                <input type="text" class="form-control" id="atb-tag"></div>
            <div class="mb-3"><label class="form-label">Date of Birth *</label>
                <input type="date" class="form-control" id="atb-dob"></div>
            <div class="mb-3"><label class="form-label">Dam (Mother)</label>
                <select class="form-select" id="atb-dam">
                    <option value="">Select dam…</option>
                </select></div>
            <div class="mb-3"><label class="form-label">Notes</label>
                <textarea class="form-control" id="atb-notes" rows="2"></textarea></div>
        </div>
        <div class="card-footer">
            <button class="btn btn-info w-100" id="atb-save-btn">
                <i class="fas fa-baby"></i> Register Calf
            </button>
        </div>
    </div>
</div>

<!-- Sale/Death Panel -->
<div id="panel-sale-death" style="display:none;">
    <div class="card shadow-sm">
        <div class="card-header bg-warning text-dark fw-bold">Sale / Death / Cull</div>
        <div class="card-body">
            <div class="mb-3"><label class="form-label">Animal *</label>
                <select class="form-select" id="atsd-animal">
                    <option value="">Select animal…</option>
                </select></div>
            <div class="mb-3"><label class="form-label">Event Type *</label>
                <select class="form-select" id="atsd-type">
                    <option value="">Select…</option>
                    <option value="sale">Sale</option>
                    <option value="death">Death</option>
                    <option value="cull">Cull</option>
                </select></div>
            <div class="mb-3"><label class="form-label">Date *</label>
                <input type="date" class="form-control" id="atsd-date"></div>
            <!-- Sale-specific fields -->
            <div id="atsd-sale-fields" style="display:none;">
                <div class="mb-3"><label class="form-label">Sale Price (Rs) *</label>
                    <input type="number" class="form-control" id="atsd-amount" step="0.01" min="0"></div>
                <div class="mb-3"><label class="form-label">Buyer</label>
                    <input type="text" class="form-control" id="atsd-counterparty"></div>
            </div>
            <div class="mb-3"><label class="form-label">Notes</label>
                <textarea class="form-control" id="atsd-notes" rows="2"></textarea></div>
        </div>
        <div class="card-footer">
            <button class="btn btn-warning w-100" id="atsd-save-btn">
                Record Event
            </button>
        </div>
    </div>
</div>

<!-- History Panel -->
<div id="panel-history" style="display:none;">
    <div class="card shadow-sm">
        <div class="card-header bg-light fw-bold">Recent Transactions</div>
        <div class="card-body p-0">
            <div class="table-responsive">
                <table class="table table-sm mb-0">
                    <thead class="table-light">
                        <tr><th>Date</th><th>Tag</th><th>Type</th><th>Amount</th><th>Party</th></tr>
                    </thead>
                    <tbody id="ath-rows">
                        <tr><td colspan="5" class="text-center text-muted">Loading…</td></tr>
                    </tbody>
                </table>
            </div>
        </div>
    </div>
</div>

</div><!-- end container -->
```

---

## app.js Changes

```javascript
'animal_transactions': true,
```

## index.html Changes

```html
<script src="s12_animal_transactions.js"></script>
```

---

## Critical Files to Read Before Starting This Session

| File | Why |
|------|-----|
| `CLAUDE.md` | Conventions |
| `livestock/00_Overview.md` | Schema + TransactionTypes + COA codes |
| `livestock/01_HerdRegistry.md` | Animals table + LiveStockRepository (already built) |
| `app/src/main/java/com/example/dairypos/DatabaseHelper.kt` | `insertAccountingTransactionAndPostJournal`, `fetchScalarDouble` |
| `app/src/main/java/com/example/dairypos/MainActivity.kt` | Bridge dispatch pattern |
| `app/src/main/assets/app.js` | bundledScreens |
| `app/src/main/assets/index.html` | Script tags |
| `app/src/main/java/com/example/dairypos/data/repository/erp/ExpenseRepository.kt` | `AccountingTransactionInput` usage reference |

---

## Verification

1. Navigate to Livestock → Transactions
2. **Purchase tab:** Enter tag A001, Female, price 50000, today's date → Record
   - Confirm animal appears in Herd Registry
   - Confirm journal entry: Dr 1500 Livestock Assets / Cr AP
3. **Birth tab:** Select A001 as dam, enter calf tag C001 → Register
   - Confirm calf appears in registry with gender C, bookValue 0
4. **Sale/Death tab:** Select A001, type=death → Record
   - Confirm A001 status becomes 'dead'
   - Confirm journal entry: Dr 1502 Loss / Cr 1500 Assets (for bookValue amount)
5. **History tab:** Confirm all 3 events appear
6. Check journal_report screen for the accounting entries
