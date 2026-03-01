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
      getAnimals(groupId)     { return this.call('getAnimals', groupId ? {groupId} : {}); },
      getAnimalById(id)       { return this.call('getAnimalById', {id}); },
      saveGroup(obj)          { return this.call('saveGroup', obj); },
      deleteGroup(id)         { return this.call('deleteGroup', {id}); },
      saveAnimal(obj)         { return this.call('saveAnimal', obj); },
      searchAnimals(q)        { return this.call('searchAnimals', {query: q}); },
      updateAnimalStatus(obj) { return this.call('updateAnimalStatus', obj); },
    },

    HerdApp: class {
      constructor() {
        this.groups        = [];
        this.animals       = [];
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
