# Livestock Module 01 — Herd Registry

> **Session startup:** Read `CLAUDE.md` + `livestock/00_Overview.md` first, then this file.
> Also read the **Critical Files** section at the bottom before touching any code.

---

## Context

The Herd Registry is the foundation of all livestock modules. It provides:
- Animal master data (tag number, breed, gender, DOB, status, lineage)
- Group/pen management (Milking Herd, Dry Cows, Heifers, Calves)
- The navigation hub (`livestock_stub`) for the entire livestock domain
- An animal profile view that other modules (health, reproduction, lactation) link into

Every other livestock module depends on animals existing in this registry.

---

## Prerequisites

- `livestock/00_Overview.md` DB migration must be implemented first (DB_VERSION = 5)
- Tables `animals` and `animalGroups` must exist in the database

---

## What This Dossier Builds

| Artifact | Location |
|----------|---------|
| `LiveStockRepository.kt` | `data/repository/erp/LiveStockRepository.kt` |
| `s12_livestock_stub.js` | `assets/s12_livestock_stub.js` |
| `s12_herd_registry.js` | `assets/s12_herd_registry.js` |
| `templates/livestock_stub.html` | `assets/templates/livestock_stub.html` |
| `templates/herd_registry.html` | `assets/templates/herd_registry.html` |
| Edits to `DatabaseHelper.kt` | Add table constants + lazy repo |
| Edits to `MainActivity.kt` | Add bridge method routing |
| Edits to `app.js` | Add 2 entries to bundledScreens |
| Edits to `index.html` | Add 2 `<script>` tags |
| Edits to `daily_stub.html` | Add Livestock tile |

---

## Repository: `LiveStockRepository.kt`

**Package:** `com.example.dairypos.data.repository.erp`
**File:** `app/src/main/java/com/example/dairypos/data/repository/erp/LiveStockRepository.kt`

```kotlin
package com.example.dairypos.data.repository.erp

import android.content.ContentValues
import com.example.dairypos.DatabaseHelper
import org.json.JSONObject

class LiveStockRepository(private val helper: DatabaseHelper) {
    private val db  get() = helper.writableDatabase
    private val rdb get() = helper.readableDatabase

    // ── Groups ─────────────────────────────────────────────────────────────

    fun getGroups(): String {
        val sql = "SELECT id, name, notes FROM ${DatabaseHelper.T_ANIMAL_GROUPS} ORDER BY name"
        return helper.fetchAll(rdb.rawQuery(sql, null)).toString()
    }

    fun saveGroup(json: String): String {
        return try {
            val obj = JSONObject(json)
            val cv = ContentValues().apply {
                put("name",  obj.getString("name"))
                put("notes", obj.optString("notes", ""))
            }
            val id = if (obj.has("id") && obj.getInt("id") > 0) {
                db.update(DatabaseHelper.T_ANIMAL_GROUPS, cv, "id=?",
                    arrayOf(obj.getInt("id").toString()))
                obj.getInt("id").toLong()
            } else {
                db.insert(DatabaseHelper.T_ANIMAL_GROUPS, null, cv)
            }
            JSONObject().put("id", id).toString()
        } catch (ex: Exception) {
            JSONObject().put("error", ex.message).toString()
        }
    }

    fun deleteGroup(id: Int): String {
        return try {
            db.delete(DatabaseHelper.T_ANIMAL_GROUPS, "id=?", arrayOf(id.toString()))
            JSONObject().put("status", "deleted").toString()
        } catch (ex: Exception) {
            JSONObject().put("error", ex.message).toString()
        }
    }

    // ── Animals ────────────────────────────────────────────────────────────

    fun getAnimals(groupId: Int? = null): String {
        val where = if (groupId != null) "WHERE a.groupId = $groupId" else ""
        val sql = """
            SELECT a.id, a.tagNumber, a.name, a.breed, a.gender, a.status,
                   a.dateOfBirth, a.bookValue,
                   g.name AS groupName,
                   dam.tagNumber AS damTag
              FROM ${DatabaseHelper.T_ANIMALS} a
              LEFT JOIN ${DatabaseHelper.T_ANIMAL_GROUPS} g ON g.id = a.groupId
              LEFT JOIN ${DatabaseHelper.T_ANIMALS} dam ON dam.id = a.damId
              $where
             ORDER BY a.tagNumber
        """.trimIndent()
        return helper.fetchAll(rdb.rawQuery(sql, null)).toString()
    }

    fun getAnimalById(id: Int): String {
        val sql = """
            SELECT a.*, g.name AS groupName, dam.tagNumber AS damTag
              FROM ${DatabaseHelper.T_ANIMALS} a
              LEFT JOIN ${DatabaseHelper.T_ANIMAL_GROUPS} g ON g.id = a.groupId
              LEFT JOIN ${DatabaseHelper.T_ANIMALS} dam ON dam.id = a.damId
             WHERE a.id = ?
        """.trimIndent()
        val arr = helper.fetchAll(rdb.rawQuery(sql, arrayOf(id.toString())))
        return if (arr.length() > 0) arr.getJSONObject(0).toString()
        else JSONObject().put("error", "not found").toString()
    }

    fun saveAnimal(json: String): String {
        return try {
            val obj = JSONObject(json)
            val cv = ContentValues().apply {
                put("tagNumber",     obj.getString("tagNumber"))
                put("name",          obj.optString("name", ""))
                put("breed",         obj.optString("breed", ""))
                put("gender",        obj.getString("gender"))
                put("dateOfBirth",   obj.optString("dateOfBirth", ""))
                put("sireInfo",      obj.optString("sireInfo", ""))
                put("notes",         obj.optString("notes", ""))
                if (obj.has("groupId") && !obj.isNull("groupId"))
                    put("groupId", obj.getInt("groupId"))
                if (obj.has("damId") && !obj.isNull("damId"))
                    put("damId", obj.getInt("damId"))
                if (obj.has("bookValue") && !obj.isNull("bookValue"))
                    put("bookValue", obj.getDouble("bookValue"))
            }
            val id = if (obj.has("id") && obj.getInt("id") > 0) {
                db.update(DatabaseHelper.T_ANIMALS, cv, "id=?",
                    arrayOf(obj.getInt("id").toString()))
                obj.getInt("id").toLong()
            } else {
                cv.put("status", "active")
                db.insert(DatabaseHelper.T_ANIMALS, null, cv)
            }
            JSONObject().put("id", id).toString()
        } catch (ex: Exception) {
            JSONObject().put("error", ex.message).toString()
        }
    }

    fun updateAnimalStatus(id: Int, status: String): String {
        return try {
            val cv = ContentValues().apply { put("status", status) }
            db.update(DatabaseHelper.T_ANIMALS, cv, "id=?", arrayOf(id.toString()))
            JSONObject().put("status", "ok").toString()
        } catch (ex: Exception) {
            JSONObject().put("error", ex.message).toString()
        }
    }

    fun searchAnimals(query: String): String {
        val q = "%$query%"
        val sql = """
            SELECT a.id, a.tagNumber, a.name, a.breed, a.gender, a.status,
                   g.name AS groupName
              FROM ${DatabaseHelper.T_ANIMALS} a
              LEFT JOIN ${DatabaseHelper.T_ANIMAL_GROUPS} g ON g.id = a.groupId
             WHERE a.tagNumber LIKE ? OR a.name LIKE ?
             ORDER BY a.tagNumber
        """.trimIndent()
        return helper.fetchAll(rdb.rawQuery(sql, arrayOf(q, q))).toString()
    }

    fun getHerdSummary(): String {
        val sql = """
            SELECT
                COUNT(*) AS total,
                SUM(CASE WHEN status='active'    THEN 1 ELSE 0 END) AS active,
                SUM(CASE WHEN status='dry'       THEN 1 ELSE 0 END) AS dry,
                SUM(CASE WHEN status='pregnant'  THEN 1 ELSE 0 END) AS pregnant,
                SUM(CASE WHEN status='sick'      THEN 1 ELSE 0 END) AS sick,
                SUM(CASE WHEN gender='F'         THEN 1 ELSE 0 END) AS females,
                SUM(CASE WHEN gender='M'         THEN 1 ELSE 0 END) AS males,
                SUM(CASE WHEN gender='C'         THEN 1 ELSE 0 END) AS calves,
                ROUND(COALESCE(SUM(bookValue),0),2) AS totalBookValue
              FROM ${DatabaseHelper.T_ANIMALS}
             WHERE status NOT IN ('sold','dead')
        """.trimIndent()
        val arr = helper.fetchAll(rdb.rawQuery(sql, null))
        return if (arr.length() > 0) arr.getJSONObject(0).toString()
        else JSONObject().toString()
    }
}
```

---

## DatabaseHelper Changes

### Add table constants (companion object)
```kotlin
internal const val T_ANIMAL_GROUPS         = "animalGroups"
internal const val T_ANIMALS               = "animals"
internal const val T_ANIMAL_TRANSACTIONS   = "animalTransactions"
internal const val T_ANIMAL_HEALTH_EVENTS  = "animalHealthEvents"
internal const val T_VACCINATION_SCHEDULES = "vaccinationSchedules"
internal const val T_ANIMAL_REPRODUCTION   = "animalReproduction"
internal const val T_ANIMAL_LACTATION      = "animalLactation"
```

### Add lazy repos (class body, with the other `val X by lazy` lines)
```kotlin
val livestock       by lazy { LiveStockRepository(this) }
val animalTxn       by lazy { AnimalTransactionRepository(this) }
val animalHealth    by lazy { AnimalHealthRepository(this) }
val animalRepro     by lazy { AnimalReproductionRepository(this) }
val animalLactation by lazy { AnimalLactationRepository(this) }
```

> Add only `livestock` in this session. Add the others in their respective dossier sessions
> to keep each session's scope minimal.

---

## MainActivity Changes

Read `MainActivity.kt` before editing. Find the `when (functionName)` dispatch block (the
WebView bridge). Add new branches following the exact existing pattern:

```kotlin
"getGroups"          -> helper.livestock.getGroups()
"saveGroup"          -> helper.livestock.saveGroup(payload)
"deleteGroup"        -> helper.livestock.deleteGroup(payload.toInt())
"getAnimals"         -> {
    val gid = if (payload.isNotEmpty() && payload != "null") payload.toInt() else null
    helper.livestock.getAnimals(gid)
}
"getAnimalById"      -> helper.livestock.getAnimalById(payload.toInt())
"saveAnimal"         -> helper.livestock.saveAnimal(payload)
"updateAnimalStatus" -> {
    val obj = JSONObject(payload)
    helper.livestock.updateAnimalStatus(obj.getInt("id"), obj.getString("status"))
}
"searchAnimals"      -> helper.livestock.searchAnimals(payload)
"getHerdSummary"     -> helper.livestock.getHerdSummary()
```

> **Important:** Match the exact signature of the existing `when` block. If it uses
> `call.argument<String>("payload")` or similar, follow that same pattern.

---

## Frontend: `s12_livestock_stub.js`

**Screen ID:** `livestock_stub`
**Template:** `templates/livestock_stub.html`
**Navigation:** Reached from `daily_stub` tile. Back button returns to `daily_stub`.

```javascript
window.screenMap['livestock_stub'] = {
  template: 'livestock_stub.html',
  script: {
    expose() {
      window.init_livestock_stub = (params = {}) => {
        // Summary card
        nativeApi.call('getHerdSummary', {}).then(raw => {
          const s = raw ? JSON.parse(raw) : {};
          const el = document.getElementById('ls-summary');
          if (el) {
            el.innerHTML = `
              <div class="d-flex gap-3 flex-wrap">
                <span class="badge bg-success">${s.active || 0} Active</span>
                <span class="badge bg-secondary">${s.dry || 0} Dry</span>
                <span class="badge bg-info text-dark">${s.pregnant || 0} Pregnant</span>
                <span class="badge bg-danger">${s.sick || 0} Sick</span>
                <span class="badge bg-warning text-dark">${s.calves || 0} Calves</span>
              </div>`;
          }
        }).catch(err => console.error('getHerdSummary', err));
      };
    }
  }
};
```

---

## Template: `templates/livestock_stub.html`

```html
<div class="stub-topbar accent-daily">
    <button class="stub-back" onclick="navigate('daily_stub')"><i class="fas fa-arrow-left"></i></button>
    <div class="stub-title">Livestock</div>
    <div class="ConsoleLog"></div>
</div>

<div class="stub-body">

    <!-- Herd summary strip -->
    <div class="stub-stock-card mb-3">
        <div id="ls-summary" class="px-3 py-2">
            <span class="text-muted small">Loading herd summary…</span>
        </div>
    </div>

    <div class="stub-section-band accent-daily">Herd</div>
    <div class="stub-grid">

        <div class="stub-tile accent-daily" onclick="navigate('herd_registry')">
            <div class="stub-icon icon-daily"><i class="fas fa-cow"></i></div>
            <div class="stub-label">Animals</div>
        </div>

        <div class="stub-tile accent-daily" onclick="navigate('animal_transactions')">
            <div class="stub-icon icon-purchase"><i class="fas fa-exchange-alt"></i></div>
            <div class="stub-label">Transactions</div>
        </div>

    </div>

    <div class="stub-section-band accent-expense">Care</div>
    <div class="stub-grid">

        <div class="stub-tile accent-expense" onclick="navigate('animal_health')">
            <div class="stub-icon icon-expense"><i class="fas fa-heartbeat"></i></div>
            <div class="stub-label">Health &amp; Vet</div>
        </div>

        <div class="stub-tile accent-expense" onclick="navigate('animal_reproduction')">
            <div class="stub-icon icon-expense"><i class="fas fa-baby"></i></div>
            <div class="stub-label">Reproduction</div>
        </div>

        <div class="stub-tile accent-reports" onclick="navigate('animal_lactation')">
            <div class="stub-icon icon-reports"><i class="fas fa-tint"></i></div>
            <div class="stub-label">Lactation</div>
        </div>

    </div>

</div>
```

---

## Frontend: `s12_herd_registry.js`

**Screen ID:** `herd_registry`
**Template:** `templates/herd_registry.html`
**UI Flow:** Two-column mobile layout.
- Left panel: Group list with animal count badge. Tap a group → loads animals for that group.
- Right panel: Animals table. Tap an animal → shows animal detail card (inline expand or navigate).
- FAB / top-right button: Add new animal form (modal or inline).
- Search bar: calls `searchAnimals`, shows results across all groups.

```javascript
window.screenMap['herd_registry'] = {
  template: 'herd_registry.html',
  script: {

    DataBridge: {
      async call(fn, payload = {}) {
        try {
          const raw = await nativeApi.call(fn, payload);
          return raw ? JSON.parse(raw) : null;
        } catch (e) {
          console.error(`[HerdReg] ${fn}`, e);
          return null;
        }
      },
      getGroups()             { return this.call('getGroups'); },
      getAnimals(groupId)     { return this.call('getAnimals', groupId ?? ''); },
      getAnimalById(id)       { return this.call('getAnimalById', String(id)); },
      saveGroup(obj)          { return this.call('saveGroup', obj); },
      deleteGroup(id)         { return this.call('deleteGroup', String(id)); },
      saveAnimal(obj)         { return this.call('saveAnimal', obj); },
      searchAnimals(q)        { return this.call('searchAnimals', q); },
      updateAnimalStatus(obj) { return this.call('updateAnimalStatus', obj); },
    },

    HerdApp: class {
      constructor() {
        this.groups       = [];
        this.animals      = [];
        this.selectedGroup = null;
      }

      async init(params = {}) {
        const DB = window.screenMap['herd_registry'].script.DataBridge;
        this.groups = await DB.getGroups() || [];
        this._renderGroups();
        this._bindSearch();
        this._bindAddAnimal();
        this._bindAddGroup();
      }

      _renderGroups() {
        const list = document.getElementById('hr-group-list');
        if (!this.groups.length) {
          list.innerHTML = '<div class="text-muted p-3">No groups. Add one.</div>';
          return;
        }
        list.innerHTML = this.groups.map(g => `
          <div class="hr-group-row px-3 py-2 border-bottom"
               data-id="${g.id}" onclick="window.HerdApp._onGroupClick(${g.id})">
            <div class="fw-semibold">${g.name}</div>
          </div>`).join('');
      }

      async _onGroupClick(groupId) {
        const DB = window.screenMap['herd_registry'].script.DataBridge;
        this.selectedGroup = groupId;
        this.animals = await DB.getAnimals(groupId) || [];
        this._renderAnimals(this.animals);
        document.getElementById('hr-animals-panel').style.display = 'block';
      }

      _renderAnimals(animals) {
        const tbl = document.getElementById('hr-animal-rows');
        if (!animals.length) {
          tbl.innerHTML = '<tr><td colspan="4" class="text-center text-muted">No animals in this group.</td></tr>';
          return;
        }
        tbl.innerHTML = animals.map(a => `
          <tr onclick="window.HerdApp._onAnimalClick(${a.id})" style="cursor:pointer;">
            <td>${a.tagNumber}</td>
            <td>${a.name || '—'}</td>
            <td>${a.gender}</td>
            <td><span class="badge bg-${this._statusColor(a.status)}">${a.status}</span></td>
          </tr>`).join('');
      }

      _statusColor(s) {
        return { active:'success', dry:'secondary', pregnant:'info',
                 sick:'danger', sold:'dark', dead:'dark' }[s] || 'secondary';
      }

      async _onAnimalClick(id) {
        const DB = window.screenMap['herd_registry'].script.DataBridge;
        const animal = await DB.getAnimalById(id);
        if (!animal) return;
        // Show inline detail card
        const detail = document.getElementById('hr-detail-card');
        detail.style.display = 'block';
        detail.innerHTML = `
          <div class="card shadow-sm mb-3">
            <div class="card-header bg-success text-white fw-bold">
              ${animal.tagNumber} — ${animal.name || 'Unnamed'}
            </div>
            <div class="card-body">
              <p class="mb-1"><strong>Breed:</strong> ${animal.breed || '—'}</p>
              <p class="mb-1"><strong>Gender:</strong> ${animal.gender}</p>
              <p class="mb-1"><strong>DOB:</strong> ${animal.dateOfBirth || '—'}</p>
              <p class="mb-1"><strong>Status:</strong> ${animal.status}</p>
              <p class="mb-1"><strong>Group:</strong> ${animal.groupName || '—'}</p>
              <p class="mb-1"><strong>Dam:</strong> ${animal.damTag || '—'}</p>
              <p class="mb-1"><strong>Book Value:</strong> ${animal.bookValue ?? '—'}</p>
              <p class="mb-0"><strong>Notes:</strong> ${animal.notes || '—'}</p>
            </div>
            <div class="card-footer d-flex gap-2">
              <button class="btn btn-sm btn-outline-primary"
                      onclick="window.HerdApp._editAnimal(${id})">Edit</button>
              <button class="btn btn-sm btn-outline-secondary"
                      onclick="navigate('animal_health', {animalId:${id}})">Health</button>
              <button class="btn btn-sm btn-outline-secondary"
                      onclick="navigate('animal_reproduction', {animalId:${id}})">Repro</button>
            </div>
          </div>`;
        detail.scrollIntoView({ behavior: 'smooth' });
      }

      _bindSearch() {
        const inp = document.getElementById('hr-search');
        if (!inp) return;
        let timer;
        inp.addEventListener('input', () => {
          clearTimeout(timer);
          timer = setTimeout(async () => {
            const q = inp.value.trim();
            if (q.length < 2) return;
            const DB = window.screenMap['herd_registry'].script.DataBridge;
            const results = await DB.searchAnimals(q) || [];
            this._renderAnimals(results);
            document.getElementById('hr-animals-panel').style.display = 'block';
          }, 300);
        });
      }

      _bindAddAnimal() {
        const btn = document.getElementById('hr-add-animal-btn');
        if (btn) btn.addEventListener('click', () => this._showAnimalForm(null));
      }

      _bindAddGroup() {
        const btn = document.getElementById('hr-add-group-btn');
        if (btn) btn.addEventListener('click', async () => {
          const name = prompt('Group name:');
          if (!name) return;
          const DB = window.screenMap['herd_registry'].script.DataBridge;
          await DB.saveGroup({ name });
          this.groups = await DB.getGroups() || [];
          this._renderGroups();
        });
      }

      _showAnimalForm(animal) {
        // Populate and show the add/edit form panel
        const form = document.getElementById('hr-animal-form');
        form.style.display = 'block';
        document.getElementById('hrf-id').value         = animal?.id ?? '';
        document.getElementById('hrf-tag').value        = animal?.tagNumber ?? '';
        document.getElementById('hrf-name').value       = animal?.name ?? '';
        document.getElementById('hrf-breed').value      = animal?.breed ?? '';
        document.getElementById('hrf-gender').value     = animal?.gender ?? '';
        document.getElementById('hrf-dob').value        = animal?.dateOfBirth ?? '';
        document.getElementById('hrf-sire').value       = animal?.sireInfo ?? '';
        document.getElementById('hrf-notes').value      = animal?.notes ?? '';
        form.scrollIntoView({ behavior: 'smooth' });
      }

      _editAnimal(id) {
        const animal = this.animals.find(a => a.id === id) || { id };
        this._showAnimalForm(animal);
      }

      async _handleAnimalSave() {
        const payload = {
          id:          parseInt(document.getElementById('hrf-id').value) || 0,
          tagNumber:   document.getElementById('hrf-tag').value.trim(),
          name:        document.getElementById('hrf-name').value.trim(),
          breed:       document.getElementById('hrf-breed').value.trim(),
          gender:      document.getElementById('hrf-gender').value,
          dateOfBirth: document.getElementById('hrf-dob').value,
          sireInfo:    document.getElementById('hrf-sire').value.trim(),
          notes:       document.getElementById('hrf-notes').value.trim(),
          groupId:     this.selectedGroup
        };
        if (!payload.tagNumber || !payload.gender) {
          alert('Tag number and gender are required.');
          return;
        }
        const DB = window.screenMap['herd_registry'].script.DataBridge;
        const res = await DB.saveAnimal(payload);
        if (res?.id) {
          document.getElementById('hr-animal-form').style.display = 'none';
          if (this.selectedGroup) await this._onGroupClick(this.selectedGroup);
        } else {
          alert('Save failed: ' + (res?.error || 'Unknown'));
        }
      }
    },

    expose() {
      const app = new this.HerdApp();
      window.HerdApp = app;
      window.init_herd_registry = (p) => app.init(p);
    }
  }
};
```

---

## Template: `templates/herd_registry.html`

```html
<style>
  .hr-group-row { cursor:pointer; transition:background .15s; }
  .hr-group-row:active { background:#f0f0f0; }
  .hr-container { padding:1rem; padding-bottom:80px; overflow-y:auto; -webkit-overflow-scrolling:touch; }
</style>

<div class="screen-topbar bg-light border-bottom d-flex align-items-center px-3 py-2">
    <button class="btn btn-outline-secondary btn-sm btn-icon me-2"
            onclick="navigate('livestock_stub')" style="width:32px;height:32px;padding:0;">
        <i class="fas fa-arrow-circle-left"></i>
    </button>
    <h5 class="mb-0 flex-grow-1 ms-2">Herd Registry</h5>
    <div class="ConsoleLog"></div>
</div>

<div class="hr-container">

    <!-- Search -->
    <div class="input-group mb-3">
        <span class="input-group-text"><i class="fas fa-search"></i></span>
        <input type="text" id="hr-search" class="form-control" placeholder="Search by tag or name…">
    </div>

    <!-- Groups panel -->
    <div class="card shadow-sm mb-3">
        <div class="card-header bg-success text-white d-flex justify-content-between align-items-center">
            <span class="fw-bold">Groups / Pens</span>
            <button id="hr-add-group-btn" class="btn btn-sm btn-light">
                <i class="fas fa-plus"></i>
            </button>
        </div>
        <div class="card-body p-0">
            <div id="hr-group-list">
                <div class="text-center text-muted py-3">Loading…</div>
            </div>
        </div>
    </div>

    <!-- Animals panel (shown after group selected) -->
    <div class="card shadow-sm mb-3" id="hr-animals-panel" style="display:none;">
        <div class="card-header bg-light d-flex justify-content-between align-items-center">
            <span class="fw-bold">Animals</span>
            <button id="hr-add-animal-btn" class="btn btn-sm btn-success">
                <i class="fas fa-plus"></i> Add Animal
            </button>
        </div>
        <div class="card-body p-0">
            <div class="table-responsive">
                <table class="table table-sm table-hover mb-0">
                    <thead class="table-light">
                        <tr>
                            <th>Tag</th><th>Name</th><th>Gender</th><th>Status</th>
                        </tr>
                    </thead>
                    <tbody id="hr-animal-rows"></tbody>
                </table>
            </div>
        </div>
    </div>

    <!-- Detail card (shown after animal tap) -->
    <div id="hr-detail-card"></div>

    <!-- Add/Edit Animal form -->
    <div class="card shadow-sm mb-3" id="hr-animal-form" style="display:none;">
        <div class="card-header bg-success text-white fw-bold">Animal Details</div>
        <div class="card-body">
            <input type="hidden" id="hrf-id">
            <div class="mb-3">
                <label class="form-label">Tag Number <span class="text-danger">*</span></label>
                <input type="text" class="form-control" id="hrf-tag">
            </div>
            <div class="mb-3">
                <label class="form-label">Name</label>
                <input type="text" class="form-control" id="hrf-name">
            </div>
            <div class="mb-3">
                <label class="form-label">Breed</label>
                <input type="text" class="form-control" id="hrf-breed">
            </div>
            <div class="mb-3">
                <label class="form-label">Gender <span class="text-danger">*</span></label>
                <select class="form-select" id="hrf-gender">
                    <option value="">Select…</option>
                    <option value="F">Female</option>
                    <option value="M">Male</option>
                    <option value="C">Calf</option>
                </select>
            </div>
            <div class="mb-3">
                <label class="form-label">Date of Birth</label>
                <input type="date" class="form-control" id="hrf-dob">
            </div>
            <div class="mb-3">
                <label class="form-label">Sire Info (external straw / bull)</label>
                <input type="text" class="form-control" id="hrf-sire">
            </div>
            <div class="mb-3">
                <label class="form-label">Notes</label>
                <textarea class="form-control" id="hrf-notes" rows="2"></textarea>
            </div>
        </div>
        <div class="card-footer d-flex gap-2">
            <button class="btn btn-success flex-grow-1"
                    onclick="window.HerdApp._handleAnimalSave()">Save Animal</button>
            <button class="btn btn-outline-secondary"
                    onclick="document.getElementById('hr-animal-form').style.display='none'">Cancel</button>
        </div>
    </div>

</div>
```

---

## app.js Changes

Add to `bundledScreens` object:
```javascript
'livestock_stub': true,
'herd_registry': true,
```

---

## index.html Changes

Add after the last `s1_` block (before `s2_` scripts):
```html
<script src="s12_livestock_stub.js"></script>
<script src="s12_herd_registry.js"></script>
```

---

## daily_stub.html Changes

Add a new Livestock tile in the Work section:
```html
<div class="stub-tile accent-daily" onclick="navigate('livestock_stub')">
    <div class="stub-icon icon-daily"><i class="fas fa-cow"></i></div>
    <div class="stub-label">Livestock</div>
</div>
```

---

## Critical Files to Read Before Starting This Session

| File | Why |
|------|-----|
| `CLAUDE.md` | Conventions |
| `livestock/00_Overview.md` | Full DB schema + migration block |
| `app/src/main/java/com/example/dairypos/DatabaseHelper.kt` | Add constants + lazy repos + DB_VERSION |
| `app/src/main/java/com/example/dairypos/MainActivity.kt` | Add bridge routing |
| `app/src/main/assets/app.js` | Add to bundledScreens |
| `app/src/main/assets/index.html` | Add script tags |
| `app/src/main/assets/templates/daily_stub.html` | Add livestock tile |
| `app/src/main/assets/s9_pay_operational_liabilities.js` | Reference screen JS pattern |

---

## Verification

1. Uninstall the app (forces `onCreate` to run) or increment `DB_VERSION` to trigger `onUpgrade`
2. Navigate `daily_stub → Livestock`
3. Confirm herd summary shows 0 counts
4. Add a group "Milking Herd" → confirm it appears in the list
5. Add an animal (tag: A001, gender: F) → confirm it appears in the Animals table
6. Tap the animal → confirm detail card shows all fields
7. Search "A001" → confirm result appears
8. Check `logcat` for any SQL errors during navigation
