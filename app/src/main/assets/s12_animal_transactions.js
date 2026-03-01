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
      getAnimals()             { return this.call('getAnimals', {}); },
      getGroups()              { return this.call('getGroups'); },
      saveAnimalPurchase(obj)  { return this.call('saveAnimalPurchase', obj); },
      recordBirth(obj)         { return this.call('recordBirth', obj); },
      saveAnimalSale(obj)      { return this.call('saveAnimalSale', obj); },
      recordAnimalDeath(obj)   { return this.call('recordAnimalDeath', obj); },
      getRecentTransactions()  { return this.call('getRecentTransactions'); },
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
        this._switchTab('purchase');
      }

      _switchTab(tab) {
        ['purchase','birth','sale-death','history'].forEach(t => {
          document.getElementById(`tab-${t}`)?.classList.toggle('active', t === tab);
          const panel = document.getElementById(`panel-${t}`);
          if (panel) panel.style.display = t === tab ? 'block' : 'none';
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
            tagNumber:        document.getElementById('atp-tag').value.trim(),
            name:             document.getElementById('atp-name').value.trim(),
            breed:            document.getElementById('atp-breed').value.trim(),
            gender:           document.getElementById('atp-gender').value,
            dateOfBirth:      document.getElementById('atp-dob').value,
            purchasePrice:    parseFloat(document.getElementById('atp-price').value) || 0,
            purchaseDate:     document.getElementById('atp-date').value,
            counterpartyName: document.getElementById('atp-vendor').value.trim(),
            notes:            document.getElementById('atp-notes').value.trim(),
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
