window.screenMap.account_types = {
    template: 'account_types.html',
    script: {
      AccountTypesApp: class {
        constructor() {
          this.selectedId = null;
          this.selectedRow = null;
        }

        init(params = {}) {
          console.log('[AccountTypes] init called', params);

          this.clearForm();
          this.renderTable();

          // Bind buttons
          document.getElementById('btnAdd').onclick    = () => this.addType();
          document.getElementById('btnUpdate').onclick = () => this.updateType();
          document.getElementById('btnDelete').onclick = () => this.deleteType();
          document.getElementById('btnClear').onclick  = () => {
            this.clearForm();
            this.renderTable();
          };
        }

        async renderTable() {
          try {
            const list = JSON.parse(await nativeApi.call('getAccountTypes'));
            const tbody = document.getElementById('types-table').querySelector('tbody');
            tbody.innerHTML = list.map(t => `
              <tr data-id="${t.id}">
                <td>${t.id}</td>
                <td>${t.name}</td>
              </tr>
            `).join('');

            tbody.querySelectorAll('tr').forEach(row => {
              row.addEventListener('click', () => {
                if (this.selectedRow) this.selectedRow.classList.remove('table-primary');
                row.classList.add('table-primary');
                this.selectedRow = row;
                this.selectedId = +row.dataset.id;

                document.getElementById('type-id').value   = this.selectedId;
                document.getElementById('type-name').value = row.cells[1].textContent;

                document.getElementById('btnAdd').disabled    = true;
                document.getElementById('btnUpdate').disabled = false;
                document.getElementById('btnDelete').disabled = false;
              });
            });
          } catch (e) {
            console.error('[AccountTypes] renderTable error', e);
            const tbody = document.getElementById('types-table').querySelector('tbody');
            tbody.innerHTML = '<tr><td colspan="2" class="text-center text-danger">Error loading types</td></tr>';
          }
        }

        clearForm() {
          if (this.selectedRow) this.selectedRow.classList.remove('table-primary');
          this.selectedRow = null;
          this.selectedId = null;

          document.getElementById('type-id').value   = '';
          document.getElementById('type-name').value = '';

          document.getElementById('btnAdd').disabled    = false;
          document.getElementById('btnUpdate').disabled = true;
          document.getElementById('btnDelete').disabled = true;
        }

        async addType() {
          const name = document.getElementById('type-name').value.trim();
          if (!name) return alert('name is required');
          try {
            await nativeApi.post('saveAccountType', { name: name });
            this.clearForm();
            this.renderTable();
          } catch (e) {
            console.error('[AccountTypes] addType error', e);
            alert('Failed to add type.');
          }
        }

        async updateType() {
          const name = document.getElementById('type-name').value.trim();
          if (!name) return alert('name is required');
          try {
            await nativeApi.post('saveAccountType', { id: this.selectedId, name: name });
            this.clearForm();
            this.renderTable();
          } catch (e) {
            console.error('[AccountTypes] updateType error', e);
            alert('Failed to update type.');
          }
        }

        async deleteType() {
          if (!this.selectedId || !confirm('Delete this type?')) return;
          try {
            await nativeApi.post('deleteAccountType', { id: this.selectedId.toString() });
            this.clearForm();
            this.renderTable();
          } catch (e) {
            console.error('[AccountTypes] deleteType error', e);
            alert('Failed to delete type.');
          }
        }
      },

      expose() {
        console.log('[AccountTypes] expose called');
        const app = new this.AccountTypesApp();
        window.AccountTypesApp = app;
        window.init_account_types = app.init.bind(app);
      }
    }
  };
