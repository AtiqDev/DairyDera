# Livestock Module 04 — Reproduction & Calving

> **Session startup:** Read `CLAUDE.md` + `livestock/00_Overview.md` first, then this file.
> Also read the **Critical Files** section at the bottom before touching any code.

---

## Context

This module tracks the full breeding cycle for each female animal:

```
Heat Detection → Insemination (AI or natural) → Pregnancy Check → Expected Calving Date
    → Calving Event → Creates calf animal record + Opens lactation cycle (module 05)
```

Key behaviors:
- One cycle record per pregnancy attempt. Multiple attempts per animal are normal.
- Calving event: creates a new row in `animals` (the calf) and sets `calfId` on the cycle.
- Calving also updates dam status → `'active'` (returning to milking) and creates a lactation
  record (module 05 uses this). The lactation creation here is minimal: just `startDate`.
- `pregnancyConfirmed`: NULL = not yet checked, 0 = negative, 1 = confirmed.

No accounting entries in this module. Reproduction is operational data only.

---

## Prerequisites

- `00_Overview.md` DB migration done (`animalReproduction`, `animalLactation` tables exist)
- `01_HerdRegistry.md` implemented (animals selectable, `animals` table writeable)

---

## What This Dossier Builds

| Artifact | Location |
|----------|---------|
| `AnimalReproductionRepository.kt` | `data/repository/erp/AnimalReproductionRepository.kt` |
| `s12_animal_reproduction.js` | `assets/s12_animal_reproduction.js` |
| `templates/animal_reproduction.html` | `assets/templates/animal_reproduction.html` |
| Edits to `DatabaseHelper.kt` | Add lazy repo |
| Edits to `MainActivity.kt` | Add bridge routing |
| Edits to `app.js` | Add to bundledScreens |
| Edits to `index.html` | Add script tag |

---

## Repository: `AnimalReproductionRepository.kt`

```kotlin
package com.example.dairypos.data.repository.erp

import android.content.ContentValues
import android.util.Log
import com.example.dairypos.DatabaseHelper
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class AnimalReproductionRepository(private val helper: DatabaseHelper) {
    private val db  get() = helper.writableDatabase
    private val rdb get() = helper.readableDatabase

    // ── Cycle management ─────────────────────────────────────────────────────

    fun getReproductionHistory(animalId: Int): String {
        val sql = """
            SELECT r.*,
                   calf.tagNumber AS calfTag
              FROM ${DatabaseHelper.T_ANIMAL_REPRODUCTION} r
              LEFT JOIN ${DatabaseHelper.T_ANIMALS} calf ON calf.id = r.calfId
             WHERE r.animalId = ?
             ORDER BY r.createdAt DESC
        """.trimIndent()
        return helper.fetchAll(rdb.rawQuery(sql, arrayOf(animalId.toString()))).toString()
    }

    // Start a new cycle: records heat detection date.
    // Payload: { animalId, heatDate, cycleNumber?, notes? }
    fun recordHeat(json: String): String {
        return try {
            val obj = JSONObject(json)
            val animalId = obj.getInt("animalId")

            // Auto-compute cycleNumber if not provided
            val cycleNumber = if (obj.has("cycleNumber") && !obj.isNull("cycleNumber"))
                obj.getInt("cycleNumber")
            else helper.fetchScalarInt(
                "SELECT COALESCE(MAX(cycleNumber), 0) + 1 FROM ${DatabaseHelper.T_ANIMAL_REPRODUCTION} WHERE animalId=?",
                arrayOf(animalId.toString())
            )

            val cv = ContentValues().apply {
                put("animalId",    animalId)
                put("heatDate",    obj.getString("heatDate"))
                put("cycleNumber", cycleNumber)
                put("outcome",     "pending")
                put("notes",       obj.optString("notes", ""))
            }
            val id = db.insert(DatabaseHelper.T_ANIMAL_REPRODUCTION, null, cv)
            JSONObject().put("id", id).put("cycleNumber", cycleNumber).toString()
        } catch (ex: Exception) {
            Log.e("ReproRepo", "recordHeat failed", ex)
            JSONObject().put("error", ex.message).toString()
        }
    }

    // Record insemination on an existing cycle.
    // Payload: { cycleId, inseminationDate, inseminationType:'AI'|'natural', bullInfo?, notes? }
    fun recordInsemination(json: String): String {
        return try {
            val obj = JSONObject(json)
            val cv = ContentValues().apply {
                put("inseminationDate", obj.getString("inseminationDate"))
                put("inseminationType", obj.getString("inseminationType"))
                put("bullInfo",         obj.optString("bullInfo", ""))
                if (obj.has("notes") && !obj.isNull("notes"))
                    put("notes", obj.getString("notes"))
            }
            db.update(DatabaseHelper.T_ANIMAL_REPRODUCTION, cv,
                "id=?", arrayOf(obj.getInt("cycleId").toString()))
            JSONObject().put("status", "ok").toString()
        } catch (ex: Exception) {
            Log.e("ReproRepo", "recordInsemination failed", ex)
            JSONObject().put("error", ex.message).toString()
        }
    }

    // Record pregnancy check result.
    // Payload: { cycleId, pregnancyCheckDate, pregnancyConfirmed:0|1, expectedCalvingDate? }
    fun updatePregnancyCheck(json: String): String {
        return try {
            val obj = JSONObject(json)
            val confirmed = obj.getInt("pregnancyConfirmed")
            val cv = ContentValues().apply {
                put("pregnancyCheckDate", obj.getString("pregnancyCheckDate"))
                put("pregnancyConfirmed", confirmed)
                if (confirmed == 1 && obj.has("expectedCalvingDate") && !obj.isNull("expectedCalvingDate"))
                    put("expectedCalvingDate", obj.getString("expectedCalvingDate"))
            }
            db.update(DatabaseHelper.T_ANIMAL_REPRODUCTION, cv,
                "id=?", arrayOf(obj.getInt("cycleId").toString()))

            // Update dam status → pregnant if confirmed
            if (confirmed == 1) {
                val animalId = helper.fetchScalarInt(
                    "SELECT animalId FROM ${DatabaseHelper.T_ANIMAL_REPRODUCTION} WHERE id=?",
                    arrayOf(obj.getInt("cycleId").toString())
                )
                if (animalId > 0) {
                    db.update(DatabaseHelper.T_ANIMALS,
                        ContentValues().apply { put("status", "pregnant") },
                        "id=?", arrayOf(animalId.toString()))
                }
            }
            JSONObject().put("status", "ok").toString()
        } catch (ex: Exception) {
            Log.e("ReproRepo", "updatePregnancyCheck failed", ex)
            JSONObject().put("error", ex.message).toString()
        }
    }

    // Record calving event:
    //  1. Creates calf animal record in `animals`
    //  2. Sets calfId + actualCalvingDate + outcome on the reproduction cycle
    //  3. Updates dam status → 'active'
    //  4. Opens a lactation record for the dam (module 05 extends this)
    //
    // Payload: { cycleId, actualCalvingDate, calfTag, calfGender,
    //            outcome:'live'|'stillbirth'|'abortion', notes? }
    fun recordCalving(json: String): String {
        return try {
            val obj     = JSONObject(json)
            val cycleId = obj.getInt("cycleId")
            val date    = obj.getString("actualCalvingDate")
            val outcome = obj.getString("outcome")

            // 1. Get dam ID from cycle
            val animalId = helper.fetchScalarInt(
                "SELECT animalId FROM ${DatabaseHelper.T_ANIMAL_REPRODUCTION} WHERE id=?",
                arrayOf(cycleId.toString())
            )
            if (animalId <= 0) return JSONObject().put("error", "Cycle not found").toString()

            // 2. Create calf record (only if live birth)
            var calfId: Long? = null
            if (outcome == "live") {
                val calfCv = ContentValues().apply {
                    put("tagNumber",   obj.getString("calfTag"))
                    put("gender",      "C")
                    put("dateOfBirth", date)
                    put("damId",       animalId)
                    put("status",      "active")
                    put("bookValue",   0.0)
                    put("notes",       obj.optString("notes", ""))
                }
                // Inherit dam's groupId if present
                val damGroupId = helper.fetchScalarInt(
                    "SELECT COALESCE(groupId, 0) FROM ${DatabaseHelper.T_ANIMALS} WHERE id=?",
                    arrayOf(animalId.toString())
                )
                if (damGroupId > 0) calfCv.put("groupId", damGroupId)
                calfId = db.insert(DatabaseHelper.T_ANIMALS, null, calfCv)
            }

            // 3. Update reproduction cycle
            val cycleCv = ContentValues().apply {
                put("actualCalvingDate", date)
                put("calfGender",        obj.optString("calfGender", ""))
                put("outcome",           outcome)
                if (calfId != null) put("calfId", calfId)
            }
            db.update(DatabaseHelper.T_ANIMAL_REPRODUCTION, cycleCv, "id=?",
                arrayOf(cycleId.toString()))

            // 4. Update dam status → active
            db.update(DatabaseHelper.T_ANIMALS,
                ContentValues().apply { put("status", "active") },
                "id=?", arrayOf(animalId.toString()))

            // 5. Open lactation record for dam
            val lactationNumber = helper.fetchScalarInt(
                "SELECT COALESCE(MAX(lactationNumber), 0) + 1 FROM ${DatabaseHelper.T_ANIMAL_LACTATION} WHERE animalId=?",
                arrayOf(animalId.toString())
            )
            db.insert(DatabaseHelper.T_ANIMAL_LACTATION, null, ContentValues().apply {
                put("animalId",        animalId)
                put("lactationNumber", lactationNumber)
                put("startDate",       date)
                put("status",          "active")
            })

            JSONObject().put("calfId", calfId ?: 0).put("lactationNumber", lactationNumber).toString()
        } catch (ex: Exception) {
            Log.e("ReproRepo", "recordCalving failed", ex)
            JSONObject().put("error", ex.message).toString()
        }
    }

    // ── Dashboard queries ────────────────────────────────────────────────────

    // Pregnant animals with expected calving dates
    fun getExpectedCalvings(): String {
        val sql = """
            SELECT r.id AS cycleId, r.animalId, a.tagNumber, a.name AS animalName,
                   r.expectedCalvingDate, r.inseminationDate,
                   CAST(julianday(r.expectedCalvingDate) - julianday('now') AS INTEGER) AS daysUntilCalving
              FROM ${DatabaseHelper.T_ANIMAL_REPRODUCTION} r
              JOIN ${DatabaseHelper.T_ANIMALS} a ON a.id = r.animalId
             WHERE r.outcome = 'pending'
               AND r.pregnancyConfirmed = 1
               AND r.expectedCalvingDate IS NOT NULL
             ORDER BY r.expectedCalvingDate ASC
        """.trimIndent()
        return helper.fetchAll(rdb.rawQuery(sql, null)).toString()
    }

    // Active (unresolved) cycles — heat detected but not yet confirmed or calved
    fun getActiveCycles(): String {
        val sql = """
            SELECT r.id, r.animalId, r.cycleNumber,
                   a.tagNumber, a.name AS animalName,
                   r.heatDate, r.inseminationDate, r.inseminationType,
                   r.pregnancyCheckDate, r.pregnancyConfirmed,
                   r.expectedCalvingDate, r.outcome
              FROM ${DatabaseHelper.T_ANIMAL_REPRODUCTION} r
              JOIN ${DatabaseHelper.T_ANIMALS} a ON a.id = r.animalId
             WHERE r.outcome = 'pending'
             ORDER BY r.heatDate DESC
        """.trimIndent()
        return helper.fetchAll(rdb.rawQuery(sql, null)).toString()
    }
}
```

---

## DatabaseHelper Changes

```kotlin
val animalRepro by lazy { AnimalReproductionRepository(this) }
```

---

## MainActivity Changes

```kotlin
"getReproductionHistory" -> {
    val id = JSONObject(payload).getInt("animalId")
    helper.animalRepro.getReproductionHistory(id)
}
"recordHeat"           -> helper.animalRepro.recordHeat(payload)
"recordInsemination"   -> helper.animalRepro.recordInsemination(payload)
"updatePregnancyCheck" -> helper.animalRepro.updatePregnancyCheck(payload)
"recordCalving"        -> helper.animalRepro.recordCalving(payload)
"getExpectedCalvings"  -> helper.animalRepro.getExpectedCalvings()
"getActiveCycles"      -> helper.animalRepro.getActiveCycles()
```

---

## Frontend: `s12_animal_reproduction.js`

**Screen ID:** `animal_reproduction`
**Template:** `templates/animal_reproduction.html`
**Navigation:** From `livestock_stub` → Reproduction tile, or from animal detail card.
Accepts optional `params.animalId` to pre-select an animal.

**UI Flow:**
- Top section: **Upcoming Calvings** card (animals with confirmed pregnancy + expected date)
- Tab 1: **Active Cycles** — list of open cycles (pending outcome)
  - Tap a cycle → expand actions: Record Insemination / Record Pregnancy Check / Record Calving
- Tab 2: **Start New Cycle** — select female animal → record heat date → create cycle
- Tab 3: **History** — select animal → full reproduction history

```javascript
window.screenMap['animal_reproduction'] = {
  template: 'animal_reproduction.html',
  script: {

    DataBridge: {
      async call(fn, payload = {}) {
        try {
          const raw = await nativeApi.call(fn, payload);
          return raw ? JSON.parse(raw) : null;
        } catch (e) { console.error(`[Repro] ${fn}`, e); return null; }
      },
      getAnimals()               { return this.call('getAnimals', ''); },
      getExpectedCalvings()      { return this.call('getExpectedCalvings'); },
      getActiveCycles()          { return this.call('getActiveCycles'); },
      getReproductionHistory(id) { return this.call('getReproductionHistory', JSON.stringify({animalId: id})); },
      recordHeat(obj)            { return this.call('recordHeat', obj); },
      recordInsemination(obj)    { return this.call('recordInsemination', obj); },
      updatePregnancyCheck(obj)  { return this.call('updatePregnancyCheck', obj); },
      recordCalving(obj)         { return this.call('recordCalving', obj); },
    },

    ReproApp: class {
      constructor() {
        this.animals      = [];
        this.activeCycles = [];
      }

      async init(params = {}) {
        const DB = window.screenMap['animal_reproduction'].script.DataBridge;
        this.animals = (await DB.getAnimals() || []).filter(a => a.gender === 'F');

        this._buildFemaleSelect('ar-animal-sel');
        this._buildFemaleSelect('arh-animal-sel');
        this._bindTabs();
        this._bindNewCycleForm();

        await this._loadUpcomingCalvings();
        await this._loadActiveCycles();

        if (params.animalId) {
          document.getElementById('arh-animal-sel').value = params.animalId;
          await this._loadHistory(params.animalId);
          this._switchTab('history');
        } else {
          this._switchTab('active');
        }
      }

      _buildFemaleSelect(elId) {
        const sel = document.getElementById(elId);
        if (!sel) return;
        sel.innerHTML = '<option value="">Select female animal…</option>' +
          this.animals.filter(a => !['sold','dead'].includes(a.status))
            .map(a => `<option value="${a.id}">${a.tagNumber}${a.name?' — '+a.name:''} [${a.status}]</option>`)
            .join('');
      }

      _switchTab(tab) {
        ['active','new-cycle','history'].forEach(t => {
          document.getElementById(`artab-${t}`)?.classList.toggle('active', t === tab);
          const panel = document.getElementById(`arpanel-${t}`);
          if (panel) panel.style.display = t === tab ? 'block' : 'none';
        });
      }

      _bindTabs() {
        ['active','new-cycle','history'].forEach(t => {
          document.getElementById(`artab-${t}`)?.addEventListener('click', () => this._switchTab(t));
        });
      }

      async _loadUpcomingCalvings() {
        const DB = window.screenMap['animal_reproduction'].script.DataBridge;
        const calvings = await DB.getExpectedCalvings() || [];
        const card = document.getElementById('ar-upcoming-calvings');
        if (!calvings.length) { card.style.display = 'none'; return; }
        card.style.display = 'block';
        const rows = calvings.map(c => {
          const days = parseInt(c.daysUntilCalving);
          const badge = days <= 7 ? 'danger' : days <= 21 ? 'warning' : 'success';
          return `<tr>
            <td>${c.tagNumber}${c.animalName?' — '+c.animalName:''}</td>
            <td>${c.expectedCalvingDate}</td>
            <td><span class="badge bg-${badge}">${days > 0 ? 'In '+days+'d' : 'Today/Overdue'}</span></td>
            <td><button class="btn btn-sm btn-success"
                        onclick="window.ReproApp._showCalvingForm(${c.cycleId})">Calve</button></td>
          </tr>`;
        }).join('');
        card.querySelector('tbody').innerHTML = rows;
      }

      async _loadActiveCycles() {
        const DB = window.screenMap['animal_reproduction'].script.DataBridge;
        this.activeCycles = await DB.getActiveCycles() || [];
        const list = document.getElementById('ar-active-list');
        if (!this.activeCycles.length) {
          list.innerHTML = '<div class="text-muted p-3">No active cycles.</div>';
          return;
        }
        list.innerHTML = this.activeCycles.map(c => `
          <div class="ar-cycle-row px-3 py-2 border-bottom" id="cycle-${c.id}">
            <div class="d-flex justify-content-between align-items-start">
              <div>
                <div class="fw-semibold">${c.tagNumber}${c.animalName?' — '+c.animalName:''}</div>
                <div class="text-muted small">Cycle #${c.cycleNumber} | Heat: ${c.heatDate || '—'}</div>
                <div class="text-muted small">
                  AI: ${c.inseminationDate || '—'} | Preg: ${c.pregnancyConfirmed === null ? 'Not checked' : c.pregnancyConfirmed ? 'Confirmed' : 'Negative'}
                </div>
              </div>
              <div class="d-flex flex-column gap-1">
                ${!c.inseminationDate ? `<button class="btn btn-sm btn-outline-info"
                  onclick="window.ReproApp._showInseminationForm(${c.id})">AI/Inseminate</button>` : ''}
                ${c.inseminationDate && c.pregnancyConfirmed === null ? `<button class="btn btn-sm btn-outline-warning"
                  onclick="window.ReproApp._showPregnancyCheckForm(${c.id})">Preg. Check</button>` : ''}
                ${c.pregnancyConfirmed === 1 ? `<button class="btn btn-sm btn-success"
                  onclick="window.ReproApp._showCalvingForm(${c.id})">Record Calving</button>` : ''}
              </div>
            </div>
            <!-- inline action forms injected by JS below -->
            <div id="cycle-form-${c.id}"></div>
          </div>`).join('');
      }

      _bindNewCycleForm() {
        const btn = document.getElementById('ar-start-cycle-btn');
        if (!btn) return;
        btn.addEventListener('click', async () => {
          const animalId = parseInt(document.getElementById('ar-animal-sel').value);
          const heatDate = document.getElementById('ar-heat-date').value;
          if (!animalId || !heatDate) { alert('Animal and heat date are required.'); return; }
          const DB = window.screenMap['animal_reproduction'].script.DataBridge;
          const res = await DB.recordHeat({ animalId, heatDate });
          if (res && !res.error) {
            alert(`Cycle #${res.cycleNumber} started.`);
            document.getElementById('ar-animal-sel').selectedIndex = 0;
            document.getElementById('ar-heat-date').value = '';
            await this._loadActiveCycles();
            this._switchTab('active');
          } else {
            alert('Failed: ' + (res?.error || 'Unknown'));
          }
        });
      }

      _showInseminationForm(cycleId) {
        const container = document.getElementById(`cycle-form-${cycleId}`);
        container.innerHTML = `
          <div class="border rounded p-2 mt-2 bg-light">
            <div class="mb-2">
              <label class="form-label small">Insemination Date</label>
              <input type="date" class="form-control form-control-sm" id="ains-date-${cycleId}"
                     value="${new Date().toISOString().slice(0,10)}">
            </div>
            <div class="mb-2">
              <label class="form-label small">Type</label>
              <select class="form-select form-select-sm" id="ains-type-${cycleId}">
                <option value="AI">AI</option>
                <option value="natural">Natural</option>
              </select>
            </div>
            <div class="mb-2">
              <label class="form-label small">Bull / AI Straw Info</label>
              <input type="text" class="form-control form-control-sm" id="ains-bull-${cycleId}">
            </div>
            <div class="d-flex gap-2">
              <button class="btn btn-sm btn-info" onclick="window.ReproApp._saveInsemination(${cycleId})">Save</button>
              <button class="btn btn-sm btn-outline-secondary" onclick="document.getElementById('cycle-form-${cycleId}').innerHTML=''">Cancel</button>
            </div>
          </div>`;
      }

      async _saveInsemination(cycleId) {
        const payload = {
          cycleId,
          inseminationDate: document.getElementById(`ains-date-${cycleId}`).value,
          inseminationType: document.getElementById(`ains-type-${cycleId}`).value,
          bullInfo:         document.getElementById(`ains-bull-${cycleId}`).value.trim(),
        };
        const DB = window.screenMap['animal_reproduction'].script.DataBridge;
        const res = await DB.recordInsemination(payload);
        if (res?.status === 'ok') await this._loadActiveCycles();
        else alert('Failed: ' + (res?.error || 'Unknown'));
      }

      _showPregnancyCheckForm(cycleId) {
        const container = document.getElementById(`cycle-form-${cycleId}`);
        container.innerHTML = `
          <div class="border rounded p-2 mt-2 bg-light">
            <div class="mb-2">
              <label class="form-label small">Check Date</label>
              <input type="date" class="form-control form-control-sm" id="apc-date-${cycleId}"
                     value="${new Date().toISOString().slice(0,10)}">
            </div>
            <div class="mb-2">
              <label class="form-label small">Result</label>
              <select class="form-select form-select-sm" id="apc-result-${cycleId}">
                <option value="1">Confirmed Pregnant</option>
                <option value="0">Negative</option>
              </select>
            </div>
            <div class="mb-2" id="apc-calving-section-${cycleId}">
              <label class="form-label small">Expected Calving Date</label>
              <input type="date" class="form-control form-control-sm" id="apc-calving-${cycleId}">
            </div>
            <div class="d-flex gap-2">
              <button class="btn btn-sm btn-warning" onclick="window.ReproApp._savePregnancyCheck(${cycleId})">Save</button>
              <button class="btn btn-sm btn-outline-secondary" onclick="document.getElementById('cycle-form-${cycleId}').innerHTML=''">Cancel</button>
            </div>
          </div>`;
      }

      async _savePregnancyCheck(cycleId) {
        const confirmed = parseInt(document.getElementById(`apc-result-${cycleId}`).value);
        const payload = {
          cycleId,
          pregnancyCheckDate: document.getElementById(`apc-date-${cycleId}`).value,
          pregnancyConfirmed: confirmed,
          expectedCalvingDate: confirmed ? document.getElementById(`apc-calving-${cycleId}`).value : null,
        };
        const DB = window.screenMap['animal_reproduction'].script.DataBridge;
        const res = await DB.updatePregnancyCheck(payload);
        if (res?.status === 'ok') {
          await this._loadActiveCycles();
          await this._loadUpcomingCalvings();
        } else alert('Failed: ' + (res?.error || 'Unknown'));
      }

      _showCalvingForm(cycleId) {
        // Find the container — could be in active list or upcoming calvings
        let container = document.getElementById(`cycle-form-${cycleId}`);
        if (!container) {
          // Show as a modal-like overlay approach: inject after upcoming calvings card
          container = document.getElementById('ar-calving-form-container');
          container.style.display = 'block';
          container.dataset.cycleId = cycleId;
        }
        const formHtml = `
          <div class="card shadow-sm mt-2">
            <div class="card-header bg-success text-white fw-bold">Record Calving — Cycle ${cycleId}</div>
            <div class="card-body">
              <div class="mb-2">
                <label class="form-label small">Calving Date *</label>
                <input type="date" class="form-control" id="acalv-date-${cycleId}"
                       value="${new Date().toISOString().slice(0,10)}">
              </div>
              <div class="mb-2">
                <label class="form-label small">Outcome *</label>
                <select class="form-select" id="acalv-outcome-${cycleId}">
                  <option value="live">Live Birth</option>
                  <option value="stillbirth">Stillbirth</option>
                  <option value="abortion">Abortion</option>
                </select>
              </div>
              <div id="acalv-calf-fields-${cycleId}">
                <div class="mb-2">
                  <label class="form-label small">Calf Tag Number *</label>
                  <input type="text" class="form-control" id="acalv-tag-${cycleId}">
                </div>
                <div class="mb-2">
                  <label class="form-label small">Calf Gender</label>
                  <select class="form-select" id="acalv-gender-${cycleId}">
                    <option value="F">Female</option>
                    <option value="M">Male</option>
                    <option value="C">Unknown / Calf</option>
                  </select>
                </div>
              </div>
              <div class="mb-2">
                <label class="form-label small">Notes</label>
                <textarea class="form-control" id="acalv-notes-${cycleId}" rows="2"></textarea>
              </div>
            </div>
            <div class="card-footer d-flex gap-2">
              <button class="btn btn-success flex-grow-1"
                      onclick="window.ReproApp._saveCalving(${cycleId})">
                <i class="fas fa-baby"></i> Save Calving
              </button>
              <button class="btn btn-outline-secondary"
                      onclick="document.getElementById('ar-calving-form-container').style.display='none'">
                Cancel
              </button>
            </div>
          </div>`;
        if (container.id === 'ar-calving-form-container') {
          container.innerHTML = formHtml;
        } else {
          container.innerHTML = formHtml;
        }
        // Toggle calf tag fields based on outcome
        document.getElementById(`acalv-outcome-${cycleId}`)?.addEventListener('change', (e) => {
          document.getElementById(`acalv-calf-fields-${cycleId}`).style.display =
            e.target.value === 'live' ? 'block' : 'none';
        });
      }

      async _saveCalving(cycleId) {
        const outcome = document.getElementById(`acalv-outcome-${cycleId}`).value;
        const payload = {
          cycleId,
          actualCalvingDate: document.getElementById(`acalv-date-${cycleId}`).value,
          outcome,
          notes: document.getElementById(`acalv-notes-${cycleId}`).value.trim(),
        };
        if (outcome === 'live') {
          payload.calfTag    = document.getElementById(`acalv-tag-${cycleId}`).value.trim();
          payload.calfGender = document.getElementById(`acalv-gender-${cycleId}`).value;
          if (!payload.calfTag) { alert('Calf tag is required for live birth.'); return; }
        }
        const DB = window.screenMap['animal_reproduction'].script.DataBridge;
        const res = await DB.recordCalving(payload);
        if (res && !res.error) {
          const msg = outcome === 'live'
            ? `Calving recorded. Calf ID: ${res.calfId}. Lactation #${res.lactationNumber} opened.`
            : 'Calving event recorded.';
          alert(msg);
          document.getElementById('ar-calving-form-container').style.display = 'none';
          await this._loadActiveCycles();
          await this._loadUpcomingCalvings();
        } else {
          alert('Failed: ' + (res?.error || 'Unknown'));
        }
      }

      async _loadHistory(animalId) {
        const DB = window.screenMap['animal_reproduction'].script.DataBridge;
        const history = await DB.getReproductionHistory(animalId) || [];
        const list = document.getElementById('arh-history');
        list.innerHTML = history.map(c => `
          <div class="px-3 py-2 border-bottom">
            <div class="d-flex justify-content-between">
              <span class="fw-semibold">Cycle #${c.cycleNumber}</span>
              <span class="badge bg-${c.outcome === 'live' ? 'success' : c.outcome === 'pending' ? 'warning' : 'secondary'}">${c.outcome}</span>
            </div>
            <div class="text-muted small">Heat: ${c.heatDate || '—'} | AI: ${c.inseminationDate || '—'}</div>
            <div class="text-muted small">Calved: ${c.actualCalvingDate || '—'} | Calf: ${c.calfTag || '—'}</div>
          </div>`).join('') || '<div class="text-muted p-3">No reproduction history.</div>';
      }
    },

    expose() {
      const app = new this.ReproApp();
      window.ReproApp = app;
      window.init_animal_reproduction = (p) => app.init(p);
    }
  }
};
```

---

## Template: `templates/animal_reproduction.html`

```html
<div class="screen-topbar bg-light border-bottom d-flex align-items-center px-3 py-2">
    <button class="btn btn-outline-secondary btn-sm btn-icon me-2"
            onclick="navigate('livestock_stub')" style="width:32px;height:32px;padding:0;">
        <i class="fas fa-arrow-circle-left"></i>
    </button>
    <h5 class="mb-0 flex-grow-1 ms-2">Reproduction &amp; Calving</h5>
    <div class="ConsoleLog"></div>
</div>

<!-- Upcoming Calvings -->
<div id="ar-upcoming-calvings" class="mx-3 mt-3" style="display:none;">
    <div class="card border-success shadow-sm">
        <div class="card-header bg-success text-white fw-bold">
            <i class="fas fa-baby"></i> Upcoming Calvings
        </div>
        <div class="table-responsive">
            <table class="table table-sm mb-0">
                <thead class="table-light">
                    <tr><th>Animal</th><th>Expected Date</th><th>Status</th><th></th></tr>
                </thead>
                <tbody></tbody>
            </table>
        </div>
    </div>
</div>

<!-- Calving form container (shown from upcoming calvings) -->
<div id="ar-calving-form-container" class="mx-3 mt-2" style="display:none;"></div>

<!-- Tabs -->
<ul class="nav nav-tabs px-3 pt-2 bg-white border-bottom mt-3">
    <li class="nav-item"><button class="nav-link" id="artab-active">Active Cycles</button></li>
    <li class="nav-item"><button class="nav-link" id="artab-new-cycle">New Cycle</button></li>
    <li class="nav-item"><button class="nav-link" id="artab-history">History</button></li>
</ul>

<div style="padding:1rem;padding-bottom:80px;overflow-y:auto;-webkit-overflow-scrolling:touch;">

<!-- Active Cycles Panel -->
<div id="arpanel-active">
    <div class="card shadow-sm">
        <div class="card-header bg-light fw-bold">Open Cycles</div>
        <div class="card-body p-0" id="ar-active-list">
            <div class="text-muted p-3">Loading…</div>
        </div>
    </div>
</div>

<!-- New Cycle Panel -->
<div id="arpanel-new-cycle" style="display:none;">
    <div class="card shadow-sm">
        <div class="card-header bg-info text-dark fw-bold">Start New Cycle (Heat Detection)</div>
        <div class="card-body">
            <div class="mb-3">
                <label class="form-label">Female Animal *</label>
                <select class="form-select" id="ar-animal-sel"></select>
            </div>
            <div class="mb-3">
                <label class="form-label">Heat Date *</label>
                <input type="date" class="form-control" id="ar-heat-date">
            </div>
        </div>
        <div class="card-footer">
            <button class="btn btn-info w-100" id="ar-start-cycle-btn">
                Start Cycle
            </button>
        </div>
    </div>
</div>

<!-- History Panel -->
<div id="arpanel-history" style="display:none;">
    <div class="mb-3">
        <label class="form-label fw-semibold">Select Animal</label>
        <select class="form-select" id="arh-animal-sel"></select>
    </div>
    <div class="card shadow-sm">
        <div class="card-header bg-light fw-bold">Reproduction History</div>
        <div class="card-body p-0" id="arh-history">
            <div class="text-muted p-3">Select an animal above.</div>
        </div>
    </div>
</div>

</div>
```

---

## app.js Changes

```javascript
'animal_reproduction': true,
```

## index.html Changes

```html
<script src="s12_animal_reproduction.js"></script>
```

---

## Critical Files to Read Before Starting This Session

| File | Why |
|------|-----|
| `CLAUDE.md` | Conventions |
| `livestock/00_Overview.md` | Schema for `animalReproduction` + `animalLactation` |
| `livestock/01_HerdRegistry.md` | `getAnimals` bridge (already built) |
| `app/src/main/java/com/example/dairypos/DatabaseHelper.kt` | `fetchScalarInt`, `fetchAll` |
| `app/src/main/java/com/example/dairypos/MainActivity.kt` | Bridge dispatch |
| `app/src/main/assets/app.js` | bundledScreens |
| `app/src/main/assets/index.html` | Script tags |

---

## Verification

1. Navigate to Livestock → Reproduction
2. **New Cycle tab:** Select a female animal → enter heat date → Start Cycle
   - Confirm cycle appears in Active Cycles tab
3. On the cycle: tap **AI/Inseminate** → enter date + type → Save
   - Confirm insemination date appears in cycle row
4. Tap **Preg. Check** → select Confirmed → enter expected calving date → Save
   - Confirm cycle shows Preg: Confirmed
   - Confirm Upcoming Calvings card shows the animal
5. Tap **Record Calving** → enter calf tag C002 → outcome: live → Save Calving
   - Confirm alert shows calf ID + lactation number
   - Confirm calf C002 appears in Herd Registry
   - Confirm dam status in registry returns to 'active'
   - Confirm `animalLactation` row exists (check via Query tool)
6. History tab: select dam → confirm the full cycle is shown
