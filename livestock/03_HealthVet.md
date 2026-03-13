# Livestock Module 03 — Health & Veterinary

> **Session startup:** Read `CLAUDE.md` + `livestock/00_Overview.md` first, then this file.
> Also read the **Critical Files** section at the bottom before touching any code.

---

## Context

This module manages the complete health record for each animal:
- **Vaccination schedules** — recurring templates (e.g., "FMD Vaccine every 180 days")
- **Per-animal health events** — vaccination, treatment, diagnosis, checkup, other
- **Overdue alerts** — animals whose next vaccination is past due
- **Vet cost linkage** — health event cost can optionally be linked to the existing
  Expense module for accounting (manual link — no auto-journal in this module)

Health events are evidence-only records. The accounting for vet costs is handled by the
existing Expense module (saveFuelExpense-style); this module only stores the clinical record.

---

## Prerequisites

- `00_Overview.md` DB migration done (tables `animalHealthEvents`, `vaccinationSchedules` exist)
- `01_HerdRegistry.md` implemented (animals are searchable)

---

## What This Dossier Builds

| Artifact | Location |
|----------|---------|
| `AnimalHealthRepository.kt` | `data/repository/erp/AnimalHealthRepository.kt` |
| `s12_animal_health.js` | `assets/s12_animal_health.js` |
| `templates/animal_health.html` | `assets/templates/animal_health.html` |
| Edits to `DatabaseHelper.kt` | Add lazy repo |
| Edits to `MainActivity.kt` | Add bridge method routing |
| Edits to `app.js` | Add to bundledScreens |
| Edits to `index.html` | Add script tag |

---

## Repository: `AnimalHealthRepository.kt`

```kotlin
package com.example.dairypos.data.repository.erp

import android.content.ContentValues
import android.util.Log
import com.example.dairypos.DatabaseHelper
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class AnimalHealthRepository(private val helper: DatabaseHelper) {
    private val db  get() = helper.writableDatabase
    private val rdb get() = helper.readableDatabase

    // ── Vaccination Schedules ────────────────────────────────────────────────

    fun getSchedules(): String {
        val sql = """
            SELECT id, name, intervalDays, notes
              FROM ${DatabaseHelper.T_VACCINATION_SCHEDULES}
             ORDER BY name
        """.trimIndent()
        return helper.fetchAll(rdb.rawQuery(sql, null)).toString()
    }

    fun saveSchedule(json: String): String {
        return try {
            val obj = JSONObject(json)
            val cv = ContentValues().apply {
                put("name",         obj.getString("name"))
                put("intervalDays", if (obj.has("intervalDays") && !obj.isNull("intervalDays"))
                                        obj.getInt("intervalDays") else null)
                put("notes",        obj.optString("notes", ""))
            }
            val id = if (obj.has("id") && obj.getInt("id") > 0) {
                db.update(DatabaseHelper.T_VACCINATION_SCHEDULES, cv, "id=?",
                    arrayOf(obj.getInt("id").toString()))
                obj.getInt("id").toLong()
            } else {
                db.insert(DatabaseHelper.T_VACCINATION_SCHEDULES, null, cv)
            }
            JSONObject().put("id", id).toString()
        } catch (ex: Exception) {
            JSONObject().put("error", ex.message).toString()
        }
    }

    fun deleteSchedule(id: Int): String {
        return try {
            db.delete(DatabaseHelper.T_VACCINATION_SCHEDULES, "id=?", arrayOf(id.toString()))
            JSONObject().put("status", "deleted").toString()
        } catch (ex: Exception) {
            JSONObject().put("error", ex.message).toString()
        }
    }

    // ── Health Events ────────────────────────────────────────────────────────

    fun getHealthEvents(animalId: Int): String {
        val sql = """
            SELECT he.id, he.eventType, he.date, he.description,
                   he.medication, he.dosage, he.vetName, he.cost,
                   he.nextDueDate, he.notes,
                   vs.name AS scheduleName
              FROM ${DatabaseHelper.T_ANIMAL_HEALTH_EVENTS} he
              LEFT JOIN ${DatabaseHelper.T_VACCINATION_SCHEDULES} vs ON vs.id = he.scheduleId
             WHERE he.animalId = ?
             ORDER BY he.date DESC
        """.trimIndent()
        return helper.fetchAll(rdb.rawQuery(sql, arrayOf(animalId.toString()))).toString()
    }

    fun saveHealthEvent(json: String): String {
        return try {
            val obj = JSONObject(json)
            val cv = ContentValues().apply {
                put("animalId",    obj.getInt("animalId"))
                put("eventType",   obj.getString("eventType"))
                put("date",        obj.getString("date"))
                put("description", obj.getString("description"))
                put("medication",  obj.optString("medication", ""))
                put("dosage",      obj.optString("dosage", ""))
                put("vetName",     obj.optString("vetName", ""))
                put("notes",       obj.optString("notes", ""))
                if (obj.has("cost") && !obj.isNull("cost"))
                    put("cost", obj.getDouble("cost"))
                if (obj.has("scheduleId") && !obj.isNull("scheduleId"))
                    put("scheduleId", obj.getInt("scheduleId"))

                // Auto-compute nextDueDate if schedule has intervalDays
                if (obj.has("scheduleId") && !obj.isNull("scheduleId")) {
                    val scheduleId = obj.getInt("scheduleId")
                    val intervalDays = helper.fetchScalarInt(
                        "SELECT COALESCE(intervalDays, 0) FROM ${DatabaseHelper.T_VACCINATION_SCHEDULES} WHERE id=?",
                        arrayOf(scheduleId.toString())
                    )
                    if (intervalDays > 0) {
                        val eventDate = LocalDate.parse(obj.getString("date"),
                            DateTimeFormatter.ISO_LOCAL_DATE)
                        val nextDue = eventDate.plusDays(intervalDays.toLong())
                            .format(DateTimeFormatter.ISO_LOCAL_DATE)
                        put("nextDueDate", nextDue)
                    }
                }
            }
            val id = db.insert(DatabaseHelper.T_ANIMAL_HEALTH_EVENTS, null, cv)
            JSONObject().put("id", id).toString()
        } catch (ex: Exception) {
            Log.e("AnimalHealthRepo", "saveHealthEvent failed", ex)
            JSONObject().put("error", ex.message).toString()
        }
    }

    fun deleteHealthEvent(id: Int): String {
        return try {
            db.delete(DatabaseHelper.T_ANIMAL_HEALTH_EVENTS, "id=?", arrayOf(id.toString()))
            JSONObject().put("status", "deleted").toString()
        } catch (ex: Exception) {
            JSONObject().put("error", ex.message).toString()
        }
    }

    // ── Overdue Alerts ───────────────────────────────────────────────────────
    // Returns health events where nextDueDate <= today AND no subsequent event
    // for the same animal + schedule exists after nextDueDate.

    fun getOverdueVaccinations(): String {
        val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val sql = """
            SELECT he.animalId, a.tagNumber, a.name AS animalName,
                   vs.name AS vaccineName, he.nextDueDate,
                   CAST(julianday(?) - julianday(he.nextDueDate) AS INTEGER) AS daysOverdue
              FROM ${DatabaseHelper.T_ANIMAL_HEALTH_EVENTS} he
              JOIN ${DatabaseHelper.T_ANIMALS} a ON a.id = he.animalId
              JOIN ${DatabaseHelper.T_VACCINATION_SCHEDULES} vs ON vs.id = he.scheduleId
             WHERE he.nextDueDate IS NOT NULL
               AND he.nextDueDate <= ?
               AND a.status NOT IN ('sold','dead')
               AND NOT EXISTS (
                   SELECT 1
                     FROM ${DatabaseHelper.T_ANIMAL_HEALTH_EVENTS} he2
                    WHERE he2.animalId  = he.animalId
                      AND he2.scheduleId = he.scheduleId
                      AND he2.date > he.date
               )
             ORDER BY he.nextDueDate ASC
        """.trimIndent()
        return helper.fetchAll(rdb.rawQuery(sql, arrayOf(today, today))).toString()
    }

    // ── Summary for animal profile ───────────────────────────────────────────

    fun getHealthSummary(animalId: Int): String {
        val sql = """
            SELECT
                COUNT(*) AS totalEvents,
                SUM(CASE WHEN eventType='vaccination' THEN 1 ELSE 0 END) AS vaccinations,
                SUM(CASE WHEN eventType='treatment'   THEN 1 ELSE 0 END) AS treatments,
                ROUND(COALESCE(SUM(cost), 0), 2) AS totalVetCost,
                MAX(date) AS lastEventDate
              FROM ${DatabaseHelper.T_ANIMAL_HEALTH_EVENTS}
             WHERE animalId = ?
        """.trimIndent()
        val arr = helper.fetchAll(rdb.rawQuery(sql, arrayOf(animalId.toString())))
        return if (arr.length() > 0) arr.getJSONObject(0).toString()
        else JSONObject().toString()
    }
}
```

---

## DatabaseHelper Changes

```kotlin
val animalHealth by lazy { AnimalHealthRepository(this) }
```

---

## MainActivity Changes

```kotlin
"getSchedules"             -> helper.animalHealth.getSchedules()
"saveSchedule"             -> helper.animalHealth.saveSchedule(payload)
"deleteSchedule"           -> helper.animalHealth.deleteSchedule(payload.toInt())
"getHealthEvents"          -> helper.animalHealth.getHealthEvents(payload.toInt())
"saveHealthEvent"          -> helper.animalHealth.saveHealthEvent(payload)
"deleteHealthEvent"        -> helper.animalHealth.deleteHealthEvent(payload.toInt())
"getOverdueVaccinations"   -> helper.animalHealth.getOverdueVaccinations()
"getHealthSummary"         -> helper.animalHealth.getHealthSummary(payload.toInt())
```

---

## Frontend: `s12_animal_health.js`

**Screen ID:** `animal_health`
**Template:** `templates/animal_health.html`
**Navigation:** From `livestock_stub` → Health & Vet tile, or from animal detail card.
Accepts optional `params.animalId` to pre-select an animal.

**UI Flow:**
- Top: **Overdue Alerts** banner (always visible — red card if any animals overdue)
- Tab 1: **Per-Animal Log** — search/select animal → show health history → add event form
- Tab 2: **Schedules** — list vaccination schedule templates → add/edit/delete

```javascript
window.screenMap['animal_health'] = {
  template: 'animal_health.html',
  script: {

    DataBridge: {
      async call(fn, payload = {}) {
        try {
          const raw = await nativeApi.call(fn, payload);
          return raw ? JSON.parse(raw) : null;
        } catch (e) { console.error(`[AnimalHealth] ${fn}`, e); return null; }
      },
      getSchedules()             { return this.call('getSchedules'); },
      saveSchedule(obj)          { return this.call('saveSchedule', obj); },
      deleteSchedule(id)         { return this.call('deleteSchedule', String(id)); },
      getAnimals()               { return this.call('getAnimals', ''); },
      getHealthEvents(id)        { return this.call('getHealthEvents', String(id)); },
      saveHealthEvent(obj)       { return this.call('saveHealthEvent', obj); },
      deleteHealthEvent(id)      { return this.call('deleteHealthEvent', String(id)); },
      getOverdueVaccinations()   { return this.call('getOverdueVaccinations'); },
    },

    HealthApp: class {
      constructor() {
        this.animals    = [];
        this.schedules  = [];
        this.selectedAnimalId = null;
      }

      async init(params = {}) {
        const DB = window.screenMap['animal_health'].script.DataBridge;
        [this.animals, this.schedules] = await Promise.all([
          DB.getAnimals(), DB.getSchedules()
        ]);

        await this._loadOverdueAlerts();
        this._buildAnimalSelect();
        this._buildScheduleSelect();
        this._bindTabs();
        this._bindAddEvent();
        this._bindScheduleForm();

        // Pre-select animal if passed
        if (params.animalId) {
          document.getElementById('ah-animal-sel').value = params.animalId;
          await this._onAnimalChange(params.animalId);
        }

        this._switchTab('log');
      }

      async _loadOverdueAlerts() {
        const DB = window.screenMap['animal_health'].script.DataBridge;
        const overdue = await DB.getOverdueVaccinations() || [];
        const banner = document.getElementById('ah-overdue-banner');
        if (!overdue.length) { banner.style.display = 'none'; return; }
        banner.style.display = 'block';
        banner.innerHTML = `
          <div class="alert alert-danger mb-3">
            <strong><i class="fas fa-exclamation-triangle"></i> ${overdue.length} Overdue Vaccination(s)</strong>
            <ul class="mb-0 mt-2">
              ${overdue.map(o => `<li>${o.tagNumber}${o.animalName ? ' ('+o.animalName+')' : ''} —
                ${o.vaccineName} — ${o.daysOverdue} day(s) overdue</li>`).join('')}
            </ul>
          </div>`;
      }

      _buildAnimalSelect() {
        const sel = document.getElementById('ah-animal-sel');
        if (!sel) return;
        sel.innerHTML = '<option value="">Select animal…</option>' +
          this.animals.filter(a => !['sold','dead'].includes(a.status))
            .map(a => `<option value="${a.id}">${a.tagNumber}${a.name ? ' — '+a.name : ''}</option>`)
            .join('');
        sel.addEventListener('change', async (e) => {
          if (e.target.value) await this._onAnimalChange(parseInt(e.target.value));
        });
      }

      _buildScheduleSelect() {
        const sel = document.getElementById('ahf-schedule');
        if (!sel) return;
        sel.innerHTML = '<option value="">None (manual)</option>' +
          this.schedules.map(s => `<option value="${s.id}">${s.name}${s.intervalDays ? ' (every '+s.intervalDays+' days)' : ''}</option>`)
            .join('');
      }

      async _onAnimalChange(animalId) {
        this.selectedAnimalId = animalId;
        const DB = window.screenMap['animal_health'].script.DataBridge;
        const events = await DB.getHealthEvents(animalId) || [];
        this._renderHealthLog(events);
        document.getElementById('ah-add-event-panel').style.display = 'block';
        document.getElementById('ahf-animal-id').value = animalId;
        document.getElementById('ah-log-panel').style.display = 'block';
      }

      _renderHealthLog(events) {
        const tbody = document.getElementById('ah-event-rows');
        if (!tbody) return;
        tbody.innerHTML = events.map(e => `
          <tr>
            <td>${e.date}</td>
            <td><span class="badge bg-secondary">${e.eventType}</span></td>
            <td>${e.description}</td>
            <td>${e.medication || '—'}</td>
            <td>${e.vetName || '—'}</td>
            <td>${e.cost != null ? 'Rs '+e.cost.toLocaleString() : '—'}</td>
            <td>${e.nextDueDate || '—'}</td>
            <td>
              <button class="btn btn-sm btn-outline-danger"
                      onclick="window.HealthApp._deleteEvent(${e.id})">
                <i class="fas fa-trash"></i>
              </button>
            </td>
          </tr>`).join('') ||
          '<tr><td colspan="8" class="text-center text-muted">No health records yet.</td></tr>';
      }

      async _deleteEvent(id) {
        if (!confirm('Delete this health record?')) return;
        const DB = window.screenMap['animal_health'].script.DataBridge;
        await DB.deleteHealthEvent(id);
        await this._onAnimalChange(this.selectedAnimalId);
      }

      _bindAddEvent() {
        document.getElementById('ahf-date')?.setAttribute('value', new Date().toISOString().slice(0,10));
        const btn = document.getElementById('ahf-save-btn');
        if (!btn) return;
        btn.addEventListener('click', async () => {
          const payload = {
            animalId:    parseInt(document.getElementById('ahf-animal-id').value),
            eventType:   document.getElementById('ahf-type').value,
            date:        document.getElementById('ahf-date').value,
            description: document.getElementById('ahf-desc').value.trim(),
            medication:  document.getElementById('ahf-medication').value.trim(),
            dosage:      document.getElementById('ahf-dosage').value.trim(),
            vetName:     document.getElementById('ahf-vet').value.trim(),
            notes:       document.getElementById('ahf-notes').value.trim(),
          };
          const cost = parseFloat(document.getElementById('ahf-cost').value);
          if (cost > 0) payload.cost = cost;
          const scheduleId = parseInt(document.getElementById('ahf-schedule').value);
          if (scheduleId > 0) payload.scheduleId = scheduleId;

          if (!payload.animalId || !payload.eventType || !payload.date || !payload.description) {
            alert('Animal, event type, date, and description are required.'); return;
          }
          const DB = window.screenMap['animal_health'].script.DataBridge;
          const res = await DB.saveHealthEvent(payload);
          if (res?.id) {
            // Clear form fields
            ['ahf-type','ahf-desc','ahf-medication','ahf-dosage','ahf-vet','ahf-cost','ahf-notes']
              .forEach(id => { const el = document.getElementById(id); if (el) el.value = ''; });
            document.getElementById('ahf-schedule').selectedIndex = 0;
            await this._onAnimalChange(this.selectedAnimalId);
            await this._loadOverdueAlerts();
          } else {
            alert('Save failed: ' + (res?.error || 'Unknown'));
          }
        });
      }

      _switchTab(tab) {
        ['log','schedules'].forEach(t => {
          document.getElementById(`ahtab-${t}`)?.classList.toggle('active', t === tab);
          const panel = document.getElementById(`ahpanel-${t}`);
          if (panel) panel.style.display = t === tab ? 'block' : 'none';
        });
      }

      _bindTabs() {
        ['log','schedules'].forEach(t => {
          document.getElementById(`ahtab-${t}`)?.addEventListener('click', () => this._switchTab(t));
        });
      }

      _bindScheduleForm() {
        const btn = document.getElementById('ahs-save-btn');
        if (!btn) return;
        btn.addEventListener('click', async () => {
          const name = document.getElementById('ahs-name').value.trim();
          const days = parseInt(document.getElementById('ahs-days').value) || null;
          if (!name) { alert('Schedule name is required.'); return; }
          const DB = window.screenMap['animal_health'].script.DataBridge;
          await DB.saveSchedule({ name, intervalDays: days });
          this.schedules = await DB.getSchedules() || [];
          this._renderScheduleList();
          this._buildScheduleSelect();
          document.getElementById('ahs-name').value = '';
          document.getElementById('ahs-days').value = '';
        });
        this._renderScheduleList();
      }

      _renderScheduleList() {
        const list = document.getElementById('ahs-list');
        if (!list) return;
        list.innerHTML = this.schedules.map(s => `
          <div class="d-flex justify-content-between align-items-center px-3 py-2 border-bottom">
            <div>
              <div class="fw-semibold">${s.name}</div>
              <div class="text-muted small">${s.intervalDays ? 'Every '+s.intervalDays+' days' : 'No recurrence'}</div>
            </div>
            <button class="btn btn-sm btn-outline-danger"
                    onclick="window.HealthApp._deleteSchedule(${s.id})">
              <i class="fas fa-trash"></i>
            </button>
          </div>`).join('') || '<div class="text-muted p-3">No schedules defined yet.</div>';
      }

      async _deleteSchedule(id) {
        if (!confirm('Delete schedule? Existing events linked to it will not be affected.')) return;
        const DB = window.screenMap['animal_health'].script.DataBridge;
        await DB.deleteSchedule(id);
        this.schedules = await DB.getSchedules() || [];
        this._renderScheduleList();
        this._buildScheduleSelect();
      }
    },

    expose() {
      const app = new this.HealthApp();
      window.HealthApp = app;
      window.init_animal_health = (p) => app.init(p);
    }
  }
};
```

---

## Template: `templates/animal_health.html`

```html
<div class="screen-topbar bg-light border-bottom d-flex align-items-center px-3 py-2">
    <button class="btn btn-outline-secondary btn-sm btn-icon me-2"
            onclick="navigate('livestock_stub')" style="width:32px;height:32px;padding:0;">
        <i class="fas fa-arrow-circle-left"></i>
    </button>
    <h5 class="mb-0 flex-grow-1 ms-2">Health &amp; Veterinary</h5>
    <div class="ConsoleLog"></div>
</div>

<!-- Overdue alerts banner -->
<div id="ah-overdue-banner" style="display:none;padding:0 1rem;"></div>

<!-- Tabs -->
<ul class="nav nav-tabs px-3 pt-2 bg-white border-bottom">
    <li class="nav-item"><button class="nav-link" id="ahtab-log">Per-Animal Log</button></li>
    <li class="nav-item"><button class="nav-link" id="ahtab-schedules">Schedules</button></li>
</ul>

<div style="padding:1rem;padding-bottom:80px;overflow-y:auto;-webkit-overflow-scrolling:touch;">

<!-- Log Panel -->
<div id="ahpanel-log">
    <!-- Animal selector -->
    <div class="mb-3">
        <label class="form-label fw-semibold">Select Animal</label>
        <select class="form-select" id="ah-animal-sel">
            <option value="">Loading…</option>
        </select>
    </div>

    <!-- History table -->
    <div class="card shadow-sm mb-3" id="ah-log-panel" style="display:none;">
        <div class="card-header bg-light fw-bold">Health History</div>
        <div class="card-body p-0">
            <div class="table-responsive">
                <table class="table table-sm mb-0">
                    <thead class="table-light">
                        <tr>
                            <th>Date</th><th>Type</th><th>Description</th>
                            <th>Medication</th><th>Vet</th><th>Cost</th><th>Next Due</th><th></th>
                        </tr>
                    </thead>
                    <tbody id="ah-event-rows"></tbody>
                </table>
            </div>
        </div>
    </div>

    <!-- Add event form -->
    <div class="card shadow-sm mb-3" id="ah-add-event-panel" style="display:none;">
        <div class="card-header bg-success text-white fw-bold">Add Health Event</div>
        <div class="card-body">
            <input type="hidden" id="ahf-animal-id">
            <div class="mb-3">
                <label class="form-label">Event Type *</label>
                <select class="form-select" id="ahf-type">
                    <option value="">Select…</option>
                    <option value="vaccination">Vaccination</option>
                    <option value="treatment">Treatment</option>
                    <option value="diagnosis">Diagnosis</option>
                    <option value="checkup">Checkup</option>
                    <option value="other">Other</option>
                </select>
            </div>
            <div class="mb-3">
                <label class="form-label">Vaccination Schedule (optional)</label>
                <select class="form-select" id="ahf-schedule">
                    <option value="">None (manual)</option>
                </select>
            </div>
            <div class="mb-3">
                <label class="form-label">Date *</label>
                <input type="date" class="form-control" id="ahf-date">
            </div>
            <div class="mb-3">
                <label class="form-label">Description *</label>
                <input type="text" class="form-control" id="ahf-desc" placeholder="What was done?">
            </div>
            <div class="mb-3">
                <label class="form-label">Medication</label>
                <input type="text" class="form-control" id="ahf-medication">
            </div>
            <div class="mb-3">
                <label class="form-label">Dosage</label>
                <input type="text" class="form-control" id="ahf-dosage">
            </div>
            <div class="mb-3">
                <label class="form-label">Vet Name</label>
                <input type="text" class="form-control" id="ahf-vet">
            </div>
            <div class="mb-3">
                <label class="form-label">Cost (Rs)</label>
                <input type="number" class="form-control" id="ahf-cost" step="0.01" min="0">
            </div>
            <div class="mb-3">
                <label class="form-label">Notes</label>
                <textarea class="form-control" id="ahf-notes" rows="2"></textarea>
            </div>
        </div>
        <div class="card-footer">
            <button class="btn btn-success w-100" id="ahf-save-btn">
                <i class="fas fa-heartbeat"></i> Save Health Event
            </button>
        </div>
    </div>
</div>

<!-- Schedules Panel -->
<div id="ahpanel-schedules" style="display:none;">
    <div class="card shadow-sm mb-3">
        <div class="card-header bg-info text-dark fw-bold">Vaccination Schedules</div>
        <div class="card-body p-0">
            <div id="ahs-list">
                <div class="text-muted p-3">Loading…</div>
            </div>
        </div>
    </div>
    <div class="card shadow-sm mb-3">
        <div class="card-header bg-light fw-bold">Add Schedule</div>
        <div class="card-body">
            <div class="mb-3">
                <label class="form-label">Vaccine / Schedule Name *</label>
                <input type="text" class="form-control" id="ahs-name" placeholder="e.g. FMD Vaccine">
            </div>
            <div class="mb-3">
                <label class="form-label">Interval (days) — leave blank for one-time</label>
                <input type="number" class="form-control" id="ahs-days" min="1">
            </div>
        </div>
        <div class="card-footer">
            <button class="btn btn-info w-100" id="ahs-save-btn">Add Schedule</button>
        </div>
    </div>
</div>

</div>
```

---

## app.js Changes

```javascript
'animal_health': true,
```

## index.html Changes

```html
<script src="s12_animal_health.js"></script>
```

---

## Critical Files to Read Before Starting This Session

| File | Why |
|------|-----|
| `CLAUDE.md` | Conventions |
| `livestock/00_Overview.md` | Schema for `animalHealthEvents`, `vaccinationSchedules` |
| `livestock/01_HerdRegistry.md` | `getAnimals` bridge method (already built) |
| `app/src/main/java/com/example/dairypos/DatabaseHelper.kt` | `fetchScalarInt`, `fetchAll` |
| `app/src/main/java/com/example/dairypos/MainActivity.kt` | Bridge dispatch |
| `app/src/main/assets/app.js` | bundledScreens |
| `app/src/main/assets/index.html` | Script tags |

---

## Verification

1. Navigate to Livestock → Health & Vet
2. **Schedules tab:** Add "FMD Vaccine" with interval 180 days → confirm it appears in list
3. **Log tab:** Select an animal → confirm empty health history shows
4. Add a vaccination event using the FMD schedule → confirm it appears in history
5. Check `nextDueDate` is populated (should be date + 180 days)
6. Go back, come back to the screen → confirm the overdue banner shows if due date is past
7. Uninstall and reinstall app → confirm `vaccinationSchedules` table persists (it's in `onCreate`)
