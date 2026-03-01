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
      getAnimals()               { return this.call('getAnimals', {}); },
      getExpectedCalvings()      { return this.call('getExpectedCalvings'); },
      getActiveCycles()          { return this.call('getActiveCycles'); },
      getReproductionHistory(id) { return this.call('getReproductionHistory', {animalId: id}); },
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
        this._bindHistorySelect();

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

      _bindHistorySelect() {
        const sel = document.getElementById('arh-animal-sel');
        if (!sel) return;
        sel.addEventListener('change', async (e) => {
          if (e.target.value) await this._loadHistory(parseInt(e.target.value));
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
        let container = document.getElementById(`cycle-form-${cycleId}`);
        if (!container) {
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
        container.innerHTML = formHtml;
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
