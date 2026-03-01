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
      getSchedules()           { return this.call('getSchedules'); },
      saveSchedule(obj)        { return this.call('saveSchedule', obj); },
      deleteSchedule(id)       { return this.call('deleteSchedule', {id}); },
      getAnimals()             { return this.call('getAnimals', {}); },
      getHealthEvents(id)      { return this.call('getHealthEvents', {animalId: id}); },
      saveHealthEvent(obj)     { return this.call('saveHealthEvent', obj); },
      deleteHealthEvent(id)    { return this.call('deleteHealthEvent', {id}); },
      getOverdueVaccinations() { return this.call('getOverdueVaccinations'); },
    },

    HealthApp: class {
      constructor() {
        this.animals          = [];
        this.schedules        = [];
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
