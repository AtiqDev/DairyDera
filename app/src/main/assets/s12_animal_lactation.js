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
      getActiveLactations()   { return this.call('getActiveLactations'); },
      getDryCows()            { return this.call('getDryCows'); },
      getLactationSummary()   { return this.call('getLactationSummary'); },
      getLactationHistory(id) { return this.call('getLactationHistory', {animalId: id}); },
      getAnimals()            { return this.call('getAnimals', {}); },
      createLactation(obj)    { return this.call('createLactation', obj); },
      recordDryOff(obj)       { return this.call('recordDryOff', obj); },
      undoDryOff(id)          { return this.call('undoDryOff', {id}); },
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
