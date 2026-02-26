window.screenMap.account = {
  template: 'account.html',
  script: {
    AccountApp: class {
      constructor() {
        this.selectedId = null;
        this.selectedRow = null;
        this.types = [];
        this.refs = {};
      }

      init(params = {}) {
        console.log('[Account] init called', params);

        this.refs = {
          code: document.getElementById('acct-code'),
          name: document.getElementById('acct-name'),
          type: document.getElementById('acct-type'),
          id:   document.getElementById('acct-id'),
          btnAdd:    document.getElementById('btnAdd'),
          btnSave:   document.getElementById('btnUpdate'), // Save button
          btnDelete: document.getElementById('btnDelete'),
          tbody:     document.getElementById('accounts-table')?.querySelector('tbody')
        };

        this.clearForm();
        this.loadAccountTypes();
        this.renderTable();

        // Bind buttons
        this.refs.btnAdd.onclick   = () => this.clearForm();       // Add clears the form
        this.refs.btnSave.onclick  = () => this.saveAccount();     // Save handles create/update
        this.refs.btnDelete.onclick= () => this.deleteAccount();   // Delete removes record

        // Live validation
        ['input','change'].forEach(ev => {
          this.refs.code.addEventListener(ev, () => this.updateButtonStates());
          this.refs.name.addEventListener(ev, () => this.updateButtonStates());
          this.refs.type.addEventListener(ev, () => this.updateButtonStates());
        });
        this.updateButtonStates();
      }

      async loadAccountTypes() {
        try {
          const raw = await nativeApi.call('getAccountTypes');
          this.types = JSON.parse(raw) || [];
          this.refs.type.innerHTML = this.types
            .map(t => `<option value="${t.id}">${t.name}</option>`)
            .join('');
        } catch (e) {
          console.error('[Account] loadAccountTypes error', e);
          this.refs.type.innerHTML = '<option value="">Error loading</option>';
          this.types = [];
        }
      }

    async renderTable() {
      try {
        const raw = await nativeApi.call('getAccounts');
        const list = JSON.parse(raw) || [];

        this.refs.tbody.innerHTML = list.map(a => `
          <tr data-id="${a.id}" data-type-name="${a.accountTypeName}">
            <td>${a.code}</td>
            <td>${a.name}</td>
            <td>${a.accountTypeName}</td>
          </tr>
        `).join('');

        this.refs.tbody.querySelectorAll('tr').forEach(row => {
          if (!row._bound) {
            row._bound = true;
            row.addEventListener('click', () => {
              console.log('[Account] row clicked', row.dataset);
              if (this.selectedRow) this.selectedRow.classList.remove('table-primary');
              row.classList.add('table-primary');
              this.selectedRow = row;
              this.selectedId = +row.dataset.id;

              this.refs.id.value   = this.selectedId;
              this.refs.code.value = row.cells[0].textContent;
              this.refs.name.value = row.cells[1].textContent;

              const typeName = row.dataset.typeName || row.cells[2].textContent;
              const typeObj = this.types.find(t => t.name === typeName);
              this.refs.type.value = typeObj ? typeObj.id : (this.refs.type.options[0]?.value || '');

              this.updateButtonStates();
            });
          }
        });

        this.updateButtonStates();
      } catch (e) {
        console.error('[Account] renderTable error', e);
        this.refs.tbody.innerHTML =
          '<tr><td colspan="3" class="text-center text-danger">Error loading accounts</td></tr>';
      }
    }


      clearForm() {
        if (this.selectedRow) this.selectedRow.classList.remove('table-primary');
        this.selectedRow = null;
        this.selectedId = null;

        this.refs.id.value   = '';   // clear hidden ID
        this.refs.code.value = '';
        this.refs.name.value = '';
        this.refs.type.value = this.refs.type.options[0]?.value || '';

        this.updateButtonStates();
      }

      updateButtonStates() {
        const codeOk = !!this.refs.code.value.trim();
        const nameOk = !!this.refs.name.value.trim();
        const typeOk = !!this.refs.type.value;

        // Save enabled only when fields valid
        this.refs.btnSave.disabled = !(codeOk && nameOk && typeOk);

        // Delete enabled only when editing existing
        this.refs.btnDelete.disabled = !this.refs.id.value;
      }

    async saveAccount() {
      // Build payload
      const payload = {
        code: this.refs.code.value.trim(),
        name: this.refs.name.value.trim(),
        accountTypeId: +this.refs.type.value
      };

      // Only include id if editing an existing record
      if (this.refs.id.value) {
        payload.id = +this.refs.id.value;
      }

      // Validate required fields
      if (!payload.code || !payload.name || !payload.accountTypeId) {
        alert('code, name, and Type are required');
        return;
      }

      try {
        await nativeApi.call('saveAccount', payload);
        // Refresh UI
        this.clearForm();
        setTimeout(() => {
              this.renderTable();
            }, 500); // 500ms delay, adjust as needed

      } catch (e) {
        console.error('[Account] saveAccount error', e);
        alert('Failed to save account.');
      }
    }

      async deleteAccount() {
        if (!this.refs.id.value || !confirm('Delete this account?')) return;
        try {
          await nativeApi.post('deleteAccount', { id: this.refs.id.value.toString() });
          this.clearForm();
          this.renderTable();
        } catch (e) {
          console.error('[Account] deleteAccount error', e);
          alert('Failed to delete account.');
        }
      }
    },

    expose() {
      const app = new this.AccountApp();
      window.AccountApp = app;
      window.init_account = app.init.bind(app);
    }
  }
};
