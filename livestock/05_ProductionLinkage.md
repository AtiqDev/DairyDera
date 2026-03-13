# Livestock Module 05 — Production Linkage (Lactation)

> **Session startup:** Read `CLAUDE.md` + `livestock/00_Overview.md` first, then this file.
> Also read the **Critical Files** section at the bottom before touching any code.

---

## Context

This module manages **lactation cycles** — the period from calving to dry-off for each cow.
Lactation records are created automatically by Module 04 (Reproduction) when a calving event
is recorded. This module adds:

- Dry-off recording (closes the active lactation cycle)
- Manual lactation creation (for animals that existed before this module was built)
- Lactation history per animal
- Dashboard: active lactating cows + dry cows

**Production Linkage Philosophy:**
The existing milk production module (`s7_produce_milk.js`, `ProductionRepository.kt`) tracks
herd-level batch production. This module does NOT change that existing system. It adds a
separate per-animal lactation lifecycle layer. If you want per-animal daily yield tracking
in a future session, that would extend this module with a `dailyAnimalYield` table.
For now, this module is purely lifecycle management.

---

## Prerequisites

- `00_Overview.md` DB migration done (`animalLactation` table exists)
- `01_HerdRegistry.md` implemented (animals accessible)
- `04_Reproduction.md` implemented (calving creates lactation records)

---

## What This Dossier Builds

| Artifact | Location |
|----------|---------|
| `AnimalLactationRepository.kt` | `data/repository/erp/AnimalLactationRepository.kt` |
| `s12_animal_lactation.js` | `assets/s12_animal_lactation.js` |
| `templates/animal_lactation.html` | `assets/templates/animal_lactation.html` |
| Edits to `DatabaseHelper.kt` | Add lazy repo |
| Edits to `MainActivity.kt` | Add bridge routing |
| Edits to `app.js` | Add to bundledScreens |
| Edits to `index.html` | Add script tag |

---

## Repository: `AnimalLactationRepository.kt`

```kotlin
package com.example.dairypos.data.repository.erp

import android.content.ContentValues
import android.util.Log
import com.example.dairypos.DatabaseHelper
import org.json.JSONObject

class AnimalLactationRepository(private val helper: DatabaseHelper) {
    private val db  get() = helper.writableDatabase
    private val rdb get() = helper.readableDatabase

    // ── Queries ──────────────────────────────────────────────────────────────

    fun getLactationHistory(animalId: Int): String {
        val sql = """
            SELECT l.id, l.lactationNumber, l.startDate, l.dryOffDate, l.status, l.notes,
                   CASE
                     WHEN l.dryOffDate IS NOT NULL THEN
                       CAST(julianday(l.dryOffDate) - julianday(l.startDate) AS INTEGER)
                     ELSE
                       CAST(julianday('now') - julianday(l.startDate) AS INTEGER)
                   END AS daysInLactation
              FROM ${DatabaseHelper.T_ANIMAL_LACTATION} l
             WHERE l.animalId = ?
             ORDER BY l.lactationNumber DESC
        """.trimIndent()
        return helper.fetchAll(rdb.rawQuery(sql, arrayOf(animalId.toString()))).toString()
    }

    // Returns all currently lactating animals (status = 'active')
    fun getActiveLactations(): String {
        val sql = """
            SELECT l.id, l.animalId, l.lactationNumber, l.startDate,
                   CAST(julianday('now') - julianday(l.startDate) AS INTEGER) AS daysInLactation,
                   a.tagNumber, a.name AS animalName, a.breed,
                   g.name AS groupName
              FROM ${DatabaseHelper.T_ANIMAL_LACTATION} l
              JOIN ${DatabaseHelper.T_ANIMALS} a ON a.id = l.animalId
              LEFT JOIN ${DatabaseHelper.T_ANIMAL_GROUPS} g ON g.id = a.groupId
             WHERE l.status = 'active'
               AND a.status NOT IN ('sold','dead')
             ORDER BY l.startDate ASC
        """.trimIndent()
        return helper.fetchAll(rdb.rawQuery(sql, null)).toString()
    }

    // Returns all dry animals (most recent lactation status = 'dry')
    fun getDryCows(): String {
        val sql = """
            SELECT l.id, l.animalId, l.lactationNumber, l.startDate, l.dryOffDate,
                   CAST(julianday('now') - julianday(l.dryOffDate) AS INTEGER) AS daysDry,
                   a.tagNumber, a.name AS animalName,
                   g.name AS groupName
              FROM ${DatabaseHelper.T_ANIMAL_LACTATION} l
              JOIN ${DatabaseHelper.T_ANIMALS} a ON a.id = l.animalId
              LEFT JOIN ${DatabaseHelper.T_ANIMAL_GROUPS} g ON g.id = a.groupId
             WHERE l.status = 'dry'
               AND a.status NOT IN ('sold','dead')
             ORDER BY l.dryOffDate DESC
        """.trimIndent()
        return helper.fetchAll(rdb.rawQuery(sql, null)).toString()
    }

    fun getLactationSummary(): String {
        val sql = """
            SELECT
                SUM(CASE WHEN l.status='active' THEN 1 ELSE 0 END) AS activeLactating,
                SUM(CASE WHEN l.status='dry'    THEN 1 ELSE 0 END) AS currentlyDry,
                ROUND(AVG(CASE WHEN l.status='active'
                    THEN CAST(julianday('now') - julianday(l.startDate) AS INTEGER) END), 0) AS avgDIM
              FROM ${DatabaseHelper.T_ANIMAL_LACTATION} l
              JOIN ${DatabaseHelper.T_ANIMALS} a ON a.id = l.animalId
             WHERE a.status NOT IN ('sold','dead')
               AND l.status IN ('active','dry')
        """.trimIndent()
        val arr = helper.fetchAll(rdb.rawQuery(sql, null))
        return if (arr.length() > 0) arr.getJSONObject(0).toString()
        else JSONObject().toString()
    }

    // ── Mutations ────────────────────────────────────────────────────────────

    // Manually create a lactation record (for animals that existed before module 05)
    // Payload: { animalId, startDate, lactationNumber?, notes? }
    fun createLactation(json: String): String {
        return try {
            val obj      = JSONObject(json)
            val animalId = obj.getInt("animalId")

            val lactationNumber = if (obj.has("lactationNumber") && !obj.isNull("lactationNumber"))
                obj.getInt("lactationNumber")
            else helper.fetchScalarInt(
                "SELECT COALESCE(MAX(lactationNumber), 0) + 1 FROM ${DatabaseHelper.T_ANIMAL_LACTATION} WHERE animalId=?",
                arrayOf(animalId.toString())
            )

            val cv = ContentValues().apply {
                put("animalId",        animalId)
                put("lactationNumber", lactationNumber)
                put("startDate",       obj.getString("startDate"))
                put("status",          "active")
                put("notes",           obj.optString("notes", ""))
            }
            val id = db.insert(DatabaseHelper.T_ANIMAL_LACTATION, null, cv)

            // Update animal status to active
            db.update(DatabaseHelper.T_ANIMALS,
                ContentValues().apply { put("status", "active") },
                "id=?", arrayOf(animalId.toString()))

            JSONObject().put("id", id).put("lactationNumber", lactationNumber).toString()
        } catch (ex: Exception) {
            Log.e("LactationRepo", "createLactation failed", ex)
            JSONObject().put("error", ex.message).toString()
        }
    }

    // Record dry-off: closes active lactation cycle
    // Payload: { lactationId, dryOffDate, notes? }
    fun recordDryOff(json: String): String {
        return try {
            val obj         = JSONObject(json)
            val lactationId = obj.getInt("lactationId")
            val dryOffDate  = obj.getString("dryOffDate")

            // Update lactation record
            val cv = ContentValues().apply {
                put("dryOffDate", dryOffDate)
                put("status",     "dry")
                if (obj.has("notes") && !obj.isNull("notes"))
                    put("notes", obj.getString("notes"))
            }
            db.update(DatabaseHelper.T_ANIMAL_LACTATION, cv, "id=?",
                arrayOf(lactationId.toString()))

            // Update animal status → dry
            val animalId = helper.fetchScalarInt(
                "SELECT animalId FROM ${DatabaseHelper.T_ANIMAL_LACTATION} WHERE id=?",
                arrayOf(lactationId.toString())
            )
            if (animalId > 0) {
                db.update(DatabaseHelper.T_ANIMALS,
                    ContentValues().apply { put("status", "dry") },
                    "id=?", arrayOf(animalId.toString()))
            }

            JSONObject().put("status", "ok").toString()
        } catch (ex: Exception) {
            Log.e("LactationRepo", "recordDryOff failed", ex)
            JSONObject().put("error", ex.message).toString()
        }
    }

    // Re-open a dry lactation back to active (if dry-off was entered by mistake)
    fun undoDryOff(lactationId: Int): String {
        return try {
            val cv = ContentValues().apply {
                put("status",    "active")
                putNull("dryOffDate")
            }
            db.update(DatabaseHelper.T_ANIMAL_LACTATION, cv, "id=?",
                arrayOf(lactationId.toString()))

            val animalId = helper.fetchScalarInt(
                "SELECT animalId FROM ${DatabaseHelper.T_ANIMAL_LACTATION} WHERE id=?",
                arrayOf(lactationId.toString())
            )
            if (animalId > 0) {
                db.update(DatabaseHelper.T_ANIMALS,
                    ContentValues().apply { put("status", "active") },
                    "id=?", arrayOf(animalId.toString()))
            }
            JSONObject().put("status", "ok").toString()
        } catch (ex: Exception) {
            JSONObject().put("error", ex.message).toString()
        }
    }
}
```

---

## DatabaseHelper Changes

```kotlin
val animalLactation by lazy { AnimalLactationRepository(this) }
```

---

## MainActivity Changes

```kotlin
"getActiveLactations"  -> helper.animalLactation.getActiveLactations()
"getDryCows"           -> helper.animalLactation.getDryCows()
"getLactationSummary"  -> helper.animalLactation.getLactationSummary()
"getLactationHistory"  -> helper.animalLactation.getLactationHistory(payload.toInt())
"createLactation"      -> helper.animalLactation.createLactation(payload)
"recordDryOff"         -> helper.animalLactation.recordDryOff(payload)
"undoDryOff"           -> helper.animalLactation.undoDryOff(payload.toInt())
```

---

## Frontend: `s12_animal_lactation.js`

**Screen ID:** `animal_lactation`
**Template:** `templates/animal_lactation.html`
**Navigation:** From `livestock_stub` → Lactation tile.

**UI Flow:**
- Top card: Summary (# lactating, # dry, avg DIM — Days In Milk)
- Tab 1: **Lactating Now** — list of all active cows, DIM shown, button to record dry-off
- Tab 2: **Dry Cows** — list of dry cows, days since dry-off shown
- Tab 3: **Add Manual** — for cows that started lactating before this system was built

```javascript
window.screenMap['animal_lactation'] = {
  template: 'animal_lactation.html',
  script: {

    DataBridge: {
      async call(fn, payload = {}) {
        try {
          const raw = await nativeApi.call(fn, payload);
          return raw ? JSON.parse(raw) : null;
        } catch (e) { console.error(`[Lactation] ${fn}`, e); return null; }
      },
      getActiveLactations()     { return this.call('getActiveLactations'); },
      getDryCows()              { return this.call('getDryCows'); },
      getLactationSummary()     { return this.call('getLactationSummary'); },
      getLactationHistory(id)   { return this.call('getLactationHistory', String(id)); },
      getAnimals()              { return this.call('getAnimals', ''); },
      createLactation(obj)      { return this.call('createLactation', obj); },
      recordDryOff(obj)         { return this.call('recordDryOff', obj); },
      undoDryOff(id)            { return this.call('undoDryOff', String(id)); },
    },

    LactationApp: class {
      constructor() { this.animals = []; }

      async init(params = {}) {
        const DB = window.screenMap['animal_lactation'].script.DataBridge;
        this.animals = await DB.getAnimals() || [];
        this._bindTabs();
        this._bindManualForm();
        await this._loadSummary();
        await this._loadActiveLactations();
        this._switchTab('active');
      }

      _switchTab(tab) {
        ['active','dry','manual'].forEach(t => {
          document.getElementById(`alttab-${t}`)?.classList.toggle('active', t === tab);
          const panel = document.getElementById(`altpanel-${t}`);
          if (panel) panel.style.display = t === tab ? 'block' : 'none';
        });
        if (tab === 'dry') this._loadDryCows();
      }

      _bindTabs() {
        ['active','dry','manual'].forEach(t =>
          document.getElementById(`alttab-${t}`)?.addEventListener('click', () => this._switchTab(t)));
      }

      async _loadSummary() {
        const DB = window.screenMap['animal_lactation'].script.DataBridge;
        const s = await DB.getLactationSummary() || {};
        const el = document.getElementById('alt-summary');
        if (el) el.innerHTML = `
          <div class="d-flex gap-3 flex-wrap">
            <span class="badge bg-success fs-6">${s.activeLactating || 0} Lactating</span>
            <span class="badge bg-secondary fs-6">${s.currentlyDry || 0} Dry</span>
            <span class="badge bg-info text-dark fs-6">Avg ${Math.round(s.avgDIM || 0)} DIM</span>
          </div>`;
      }

      async _loadActiveLactations() {
        const DB = window.screenMap['animal_lactation'].script.DataBridge;
        const rows = await DB.getActiveLactations() || [];
        const list = document.getElementById('alt-active-list');
        list.innerHTML = rows.map(r => `
          <div class="d-flex justify-content-between align-items-center px-3 py-2 border-bottom">
            <div>
              <div class="fw-semibold">${r.tagNumber}${r.animalName ? ' — '+r.animalName : ''}</div>
              <div class="text-muted small">
                Lactation #${r.lactationNumber} | Started: ${r.startDate}
              </div>
              <div class="text-muted small">
                <span class="badge bg-success">${r.daysInLactation} DIM</span>
                ${r.groupName ? '| '+r.groupName : ''}
              </div>
            </div>
            <button class="btn btn-sm btn-outline-secondary"
                    onclick="window.LactationApp._showDryOffForm(${r.id}, '${r.tagNumber}')">
              Dry Off
            </button>
          </div>`).join('') ||
          '<div class="text-muted p-3">No active lactations. Record calvings in the Reproduction module.</div>';
      }

      async _loadDryCows() {
        const DB = window.screenMap['animal_lactation'].script.DataBridge;
        const rows = await DB.getDryCows() || [];
        const list = document.getElementById('alt-dry-list');
        list.innerHTML = rows.map(r => `
          <div class="d-flex justify-content-between align-items-center px-3 py-2 border-bottom">
            <div>
              <div class="fw-semibold">${r.tagNumber}${r.animalName ? ' — '+r.animalName : ''}</div>
              <div class="text-muted small">
                Dried off: ${r.dryOffDate} | ${r.daysDry} day(s) dry
              </div>
            </div>
            <button class="btn btn-sm btn-outline-warning"
                    onclick="window.LactationApp._undoDryOff(${r.id})">
              Undo
            </button>
          </div>`).join('') ||
          '<div class="text-muted p-3">No dry cows.</div>';
      }

      _showDryOffForm(lactationId, tagNumber) {
        const existing = document.getElementById('alt-dryoff-form');
        if (existing) existing.remove();
        const div = document.createElement('div');
        div.id = 'alt-dryoff-form';
        div.className = 'card shadow-sm mt-3 mx-0';
        div.innerHTML = `
          <div class="card-header bg-secondary text-white fw-bold">Dry Off — ${tagNumber}</div>
          <div class="card-body">
            <div class="mb-3">
              <label class="form-label">Dry-Off Date *</label>
              <input type="date" class="form-control" id="dof-date"
                     value="${new Date().toISOString().slice(0,10)}">
            </div>
            <div class="mb-3">
              <label class="form-label">Notes</label>
              <textarea class="form-control" id="dof-notes" rows="2"></textarea>
            </div>
          </div>
          <div class="card-footer d-flex gap-2">
            <button class="btn btn-secondary flex-grow-1"
                    onclick="window.LactationApp._saveDryOff(${lactationId})">
              Confirm Dry Off
            </button>
            <button class="btn btn-outline-secondary"
                    onclick="document.getElementById('alt-dryoff-form').remove()">
              Cancel
            </button>
          </div>`;
        document.getElementById('alt-active-list').after(div);
        div.scrollIntoView({ behavior: 'smooth' });
      }

      async _saveDryOff(lactationId) {
        const date  = document.getElementById('dof-date').value;
        const notes = document.getElementById('dof-notes').value.trim();
        if (!date) { alert('Dry-off date is required.'); return; }
        const DB = window.screenMap['animal_lactation'].script.DataBridge;
        const res = await DB.recordDryOff({ lactationId, dryOffDate: date, notes });
        if (res?.status === 'ok') {
          document.getElementById('alt-dryoff-form')?.remove();
          await this._loadSummary();
          await this._loadActiveLactations();
        } else {
          alert('Failed: ' + (res?.error || 'Unknown'));
        }
      }

      async _undoDryOff(lactationId) {
        if (!confirm('Undo dry-off and return this cow to active lactation?')) return;
        const DB = window.screenMap['animal_lactation'].script.DataBridge;
        const res = await DB.undoDryOff(lactationId);
        if (res?.status === 'ok') {
          await this._loadSummary();
          await this._loadDryCows();
          await this._loadActiveLactations();
        } else {
          alert('Failed: ' + (res?.error || 'Unknown'));
        }
      }

      _bindManualForm() {
        // Build animal selector for manual form
        const sel = document.getElementById('altm-animal');
        if (sel) {
          sel.innerHTML = '<option value="">Select female animal…</option>' +
            this.animals.filter(a => a.gender === 'F' && !['sold','dead'].includes(a.status))
              .map(a => `<option value="${a.id}">${a.tagNumber}${a.name?' — '+a.name:''}</option>`)
              .join('');
        }
        const btn = document.getElementById('altm-save-btn');
        if (!btn) return;
        btn.addEventListener('click', async () => {
          const animalId  = parseInt(document.getElementById('altm-animal').value);
          const startDate = document.getElementById('altm-start').value;
          const notes     = document.getElementById('altm-notes').value.trim();
          if (!animalId || !startDate) {
            alert('Animal and start date are required.'); return;
          }
          const DB = window.screenMap['animal_lactation'].script.DataBridge;
          const res = await DB.createLactation({ animalId, startDate, notes });
          if (res?.id) {
            alert(`Lactation #${res.lactationNumber} created.`);
            document.getElementById('altm-animal').selectedIndex = 0;
            document.getElementById('altm-start').value = '';
            document.getElementById('altm-notes').value = '';
            await this._loadSummary();
            await this._loadActiveLactations();
            this._switchTab('active');
          } else {
            alert('Failed: ' + (res?.error || 'Unknown'));
          }
        });
      }
    },

    expose() {
      const app = new this.LactationApp();
      window.LactationApp = app;
      window.init_animal_lactation = (p) => app.init(p);
    }
  }
};
```

---

## Template: `templates/animal_lactation.html`

```html
<div class="screen-topbar bg-light border-bottom d-flex align-items-center px-3 py-2">
    <button class="btn btn-outline-secondary btn-sm btn-icon me-2"
            onclick="navigate('livestock_stub')" style="width:32px;height:32px;padding:0;">
        <i class="fas fa-arrow-circle-left"></i>
    </button>
    <h5 class="mb-0 flex-grow-1 ms-2">Lactation Management</h5>
    <div class="ConsoleLog"></div>
</div>

<!-- Summary strip -->
<div class="px-3 py-2 border-bottom bg-white">
    <div id="alt-summary" class="text-muted small">Loading…</div>
</div>

<!-- Tabs -->
<ul class="nav nav-tabs px-3 pt-2 bg-white border-bottom">
    <li class="nav-item"><button class="nav-link" id="alttab-active">Lactating</button></li>
    <li class="nav-item"><button class="nav-link" id="alttab-dry">Dry Cows</button></li>
    <li class="nav-item"><button class="nav-link" id="alttab-manual">Add Manual</button></li>
</ul>

<div style="padding:1rem;padding-bottom:80px;overflow-y:auto;-webkit-overflow-scrolling:touch;">

<!-- Active Panel -->
<div id="altpanel-active">
    <div class="card shadow-sm">
        <div class="card-header bg-success text-white fw-bold">Currently Lactating</div>
        <div class="card-body p-0" id="alt-active-list">
            <div class="text-muted p-3">Loading…</div>
        </div>
    </div>
</div>

<!-- Dry Panel -->
<div id="altpanel-dry" style="display:none;">
    <div class="card shadow-sm">
        <div class="card-header bg-secondary text-white fw-bold">Dry Cows</div>
        <div class="card-body p-0" id="alt-dry-list">
            <div class="text-muted p-3">Loading…</div>
        </div>
    </div>
</div>

<!-- Manual Panel -->
<div id="altpanel-manual" style="display:none;">
    <div class="card shadow-sm">
        <div class="card-header bg-info text-dark fw-bold">Manually Add Lactation</div>
        <div class="card-body">
            <p class="text-muted small mb-3">
                Use this to add lactation records for cows that were already milking before
                this system was set up.
            </p>
            <div class="mb-3">
                <label class="form-label">Female Animal *</label>
                <select class="form-select" id="altm-animal"></select>
            </div>
            <div class="mb-3">
                <label class="form-label">Lactation Start Date *</label>
                <input type="date" class="form-control" id="altm-start">
            </div>
            <div class="mb-3">
                <label class="form-label">Notes</label>
                <textarea class="form-control" id="altm-notes" rows="2"></textarea>
            </div>
        </div>
        <div class="card-footer">
            <button class="btn btn-info w-100" id="altm-save-btn">
                <i class="fas fa-tint"></i> Add Lactation Record
            </button>
        </div>
    </div>
</div>

</div>
```

---

## app.js Changes

```javascript
'animal_lactation': true,
```

## index.html Changes

```html
<script src="s12_animal_lactation.js"></script>
```

---

## Critical Files to Read Before Starting This Session

| File | Why |
|------|-----|
| `CLAUDE.md` | Conventions |
| `livestock/00_Overview.md` | Schema for `animalLactation` |
| `livestock/01_HerdRegistry.md` | `getAnimals` bridge (already built) |
| `livestock/04_Reproduction.md` | Calving creates the lactation record (understand what already exists) |
| `app/src/main/java/com/example/dairypos/DatabaseHelper.kt` | `fetchScalarInt`, `fetchAll` |
| `app/src/main/java/com/example/dairypos/MainActivity.kt` | Bridge dispatch |
| `app/src/main/assets/app.js` | bundledScreens |
| `app/src/main/assets/index.html` | Script tags |

---

## Verification

1. **From module 04:** Record a calving event → confirm lactation record auto-created
   - Query: `SELECT * FROM animalLactation` via Query tool
2. Navigate to Livestock → Lactation
3. **Lactating tab:** Confirm the newly calved cow appears with DIM count
4. Tap **Dry Off** → enter dry-off date → Confirm
   - Cow disappears from Lactating tab
   - Cow appears in Dry Cows tab with days-dry counter
   - Animal status in Herd Registry updated to 'dry'
5. Tap **Undo** on dry cow → confirm cow returns to Lactating tab
6. **Add Manual tab:** Select a female → enter start date → Add
   - Confirm new lactation row in Lactating tab
7. Summary strip reflects correct counts after each operation

---

## Future Extension: Per-Animal Daily Yield

If per-animal daily milk yield tracking is needed in a future session, extend this module by:

1. Adding table `dailyAnimalYield (id, animalId, lactationId, date, yieldLiters, session)`
2. Adding `AnimalLactationRepository.recordDailyYield(json)` and `getDailyYield(animalId, fromDate, toDate)`
3. Adding a yield entry form to the Lactating tab (expandable per-animal row)

This is out of scope for this session. Do NOT implement unless explicitly asked.
