window.screenMap.uoms = {
  template: 'uoms.html',
  script: {
    UomsApp: class {
      constructor() {
        // UOM state
        this.selectedUomId = null;
        this.selectedUomRow = null;

        // Conversion state
        this.conversions = [];
        this.selectedConvId = null;
        this.selectedConvRow = null;
      }

      init(params = {}) {
        console.log('[Uoms] init called', params);

        // Initial form state
        this.clearUomForm();
        this.clearConversionForm();

        // Load data and render
        this.loadUomOptions();
        this.renderUomsTable();
        this.renderConversionsTable();

        // Bind UOM actions
        document.getElementById('btnUomNew').onclick = () => this.clearUomForm();
        document.getElementById('btnUomSave').onclick = () => this.saveUom();
        document.getElementById('btnUomDelete').onclick = () => this.deleteUom();

        // Bind Conversion actions
        document.getElementById('btnConvNew').onclick = () => this.clearConversionForm();
        document.getElementById('btnConvSave').onclick = () => this.saveConversion();
        document.getElementById('btnConvDelete').onclick = () => this.deleteConversion();

        // Live validation bindings
        document.getElementById('uom-name').addEventListener('input', () => this.updateUomSaveButton());
        document.getElementById('uom-type').addEventListener('input', () => this.updateUomSaveButton());

        document.getElementById('from-uom').addEventListener('change', () => this.updateConversionSaveButton());
        document.getElementById('to-uom').addEventListener('change', () => this.updateConversionSaveButton());
        document.getElementById('conversion-factor').addEventListener('input', () => this.updateConversionSaveButton());
      }

      // ------- UOMs -------
      async loadUomOptions() {
        try {
          const uoms = JSON.parse(await nativeApi.call('getAllUnits'));
          const opts = ['<option value="">Select UOM</option>']
            .concat(uoms.map(u => `<option value="${u.id}">${u.name}</option>`))
            .join('');
          document.getElementById('from-uom').innerHTML = opts;
          document.getElementById('to-uom').innerHTML = opts;
        } catch (e) {
          console.error('[Uoms] loadUomOptions error', e);
          document.getElementById('from-uom').innerHTML = '<option value="">Select UOM</option>';
          document.getElementById('to-uom').innerHTML = '<option value="">Select UOM</option>';
        }
      }

      async renderUomsTable() {
        try {
          const uoms = JSON.parse(await nativeApi.call('getAllUnits'));
          const tbody = document.getElementById('uoms-table').querySelector('tbody');

          tbody.innerHTML = uoms.map(u => `
              <tr data-id="${u.id}">
                <td>${u.id}</td>
                <td>${u.name}</td>
                <td>${u.type}</td>
              </tr>
            `).join('');

          tbody.querySelectorAll('tr').forEach(row => {
            row.addEventListener('click', () => {
              if (this.selectedUomRow) this.selectedUomRow.classList.remove('table-primary');
              row.classList.add('table-primary');
              this.selectedUomRow = row;
              this.selectedUomId = +row.dataset.id;

              document.getElementById('uom-id').value = this.selectedUomId;
              document.getElementById('uom-name').value = row.cells[1].textContent;
              document.getElementById('uom-type').value = row.cells[2].textContent;

              document.getElementById('btnUomNew').disabled = false;
              document.getElementById('btnUomDelete').disabled = false;
              this.updateUomSaveButton();
            });
          });
        } catch (e) {
          console.error('[Uoms] renderUomsTable error', e);
          const tbody = document.getElementById('uoms-table').querySelector('tbody');
          tbody.innerHTML = '<tr><td colspan="3" class="text-center text-danger">Error loading UOMs</td></tr>';
        }
      }

      clearUomForm() {
        if (this.selectedUomRow) this.selectedUomRow.classList.remove('table-primary');
        this.selectedUomRow = null;
        this.selectedUomId = null;

        document.getElementById('uom-id').value = '';
        document.getElementById('uom-name').value = '';
        document.getElementById('uom-type').value = '';

        document.getElementById('btnUomNew').disabled = false;
        document.getElementById('btnUomDelete').disabled = true;
        this.updateUomSaveButton();
      }

      updateUomSaveButton() {
        const name = document.getElementById('uom-name').value.trim();
        const type = document.getElementById('uom-type').value.trim();
        document.getElementById('btnUomSave').disabled = !(name && type);
      }

      async saveUom() {
        const id = +document.getElementById('uom-id').value || 0;
        const name = document.getElementById('uom-name').value.trim();
        const type = document.getElementById('uom-type').value.trim();
        if (!name || !type) return alert('name and Type are required');

        try {
          await nativeApi.post('saveUnit', { id: id, name: name, type: type });
          this.clearUomForm();
          this.renderUomsTable();
          this.loadUomOptions();
          this.renderConversionsTable();
        } catch (e) {
          console.error('[Uoms] saveUnit error', e);
          alert('Failed to save UOM.');
        }
      }

      async deleteUom() {
        if (!this.selectedUomId || !confirm('Delete this UOM?')) return;
        try {
          await nativeApi.post('deleteUnit', { unitId: this.selectedUomId.toString() });
          this.clearUomForm();
          this.renderUomsTable();
          this.loadUomOptions();
          this.renderConversionsTable();
        } catch (e) {
          console.error('[Uoms] deleteUnit error', e);
          alert('Failed to delete UOM.');
        }
      }

      // ------- Conversions -------
      async renderConversionsTable() {
        try {
          this.conversions = JSON.parse(await nativeApi.call('getAllConversions'));
          const tbody = document.getElementById('conversions-table').querySelector('tbody');

          tbody.innerHTML = this.conversions.map(c => `
              <tr data-id="${c.id}">
                <td>${c.from_name}</td>
                <td>${c.to_name}</td>
                <td>${c.conversionFactor}</td>
              </tr>
            `).join('');

          tbody.querySelectorAll('tr').forEach(row => {
            row.addEventListener('click', () => {
              if (this.selectedConvRow) this.selectedConvRow.classList.remove('table-primary');
              row.classList.add('table-primary');
              this.selectedConvRow = row;
              this.selectedConvId = +row.dataset.id;

              const conv = this.conversions.find(c => c.id === this.selectedConvId);
              document.getElementById('conversion-id').value = conv.id;
              document.getElementById('from-uom').value = conv.fromUomId;
              document.getElementById('to-uom').value = conv.toUomId;
              document.getElementById('conversion-factor').value = conv.conversionFactor;

              document.getElementById('btnConvNew').disabled = false;
              document.getElementById('btnConvDelete').disabled = false;
              this.updateConversionSaveButton();
            });
          });
        } catch (e) {
          console.error('[Uoms] renderConversionsTable error', e);
          const tbody = document.getElementById('conversions-table').querySelector('tbody');
          tbody.innerHTML = '<tr><td colspan="3" class="text-center text-danger">Error loading conversions</td></tr>';
        }
      }

      clearConversionForm() {
        if (this.selectedConvRow) this.selectedConvRow.classList.remove('table-primary');
        this.selectedConvRow = null;
        this.selectedConvId = null;

        document.getElementById('conversion-id').value = '';
        document.getElementById('from-uom').value = '';
        document.getElementById('to-uom').value = '';
        document.getElementById('conversion-factor').value = '';

        document.getElementById('btnConvNew').disabled = false;
        document.getElementById('btnConvSave').disabled = true;
        document.getElementById('btnConvDelete').disabled = true;
        this.updateConversionSaveButton();
      }

      updateConversionSaveButton() {
        const fromUom = document.getElementById('from-uom').value;
        const toUom = document.getElementById('to-uom').value;
        const factor = document.getElementById('conversion-factor').value;
        document.getElementById('btnConvSave').disabled = !(fromUom && toUom && factor);
      }

      async saveConversion() {
        const id = +document.getElementById('conversion-id').value || 0;
        const fromId = +document.getElementById('from-uom').value;
        const toId = +document.getElementById('to-uom').value;
        const factor = parseFloat(document.getElementById('conversion-factor').value);

        if (!fromId || !toId || !factor || fromId === toId) {
          return alert('Select two different UOMs and enter a valid factor.');
        }

        try {
          await nativeApi.post('saveConversion', {
            id: id,
            from_unit_id: fromId,
            to_unit_id: toId,
            conversion_factor: factor
          });
          this.clearConversionForm();
          this.loadUomOptions();
          this.renderConversionsTable();
        } catch (e) {
          console.error('[Uoms] saveConversion error', e);
          alert('Failed to save conversion.');
        }
      }

      async deleteConversion() {
        if (!this.selectedConvId || !confirm('Delete this conversion?')) return;
        try {
          await nativeApi.post('deleteConversion', { id: this.selectedConvId.toString() });
          this.clearConversionForm();
          this.loadUomOptions();
          this.renderConversionsTable();
        } catch (e) {
          console.error('[Uoms] deleteConversion error', e);
          alert('Failed to delete conversion.');
        }
      }
    },

    expose() {
      console.log('[Uoms] expose called');
      const app = new this.UomsApp();
      window.UomsApp = app;
      window.init_uoms = app.init.bind(app);
    }
  }
};
