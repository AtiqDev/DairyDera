window.screenMap.mix_milk = {
  template: 'mix_milk.html',
  script: {
    MixMilkApp: class {
      constructor() {
        this.tableBody = null;
        this.btnMixSave = null;
        this.noQtyMsg = null;
      }

      init(params = {}) {
        console.log('[MixMilk] init called', params);
        this.tableBody = document.querySelector('#tblRawStock tbody');
        this.btnMixSave = document.getElementById('btnMixSave');
        this.noQtyMsg = document.getElementById('noQtyMsg');

        this.btnMixSave.addEventListener('click', () => this.saveMixBatch());
        this.loadRawStock();
      }

      async loadRawStock() {
        this.tableBody.innerHTML =
          '<tr><td colspan="5" class="text-center text-muted">Loading...</td></tr>';

        try {
          const response = await nativeApi.call('getRawStockSummary');
          const data = JSON.parse(response);
          const items = data.stock || [];

          if (!items.length) {
            this.tableBody.innerHTML =
              '<tr><td colspan="5" class="text-center text-muted">No raw stock found</td></tr>';
            this.btnMixSave.disabled = true;
            this.noQtyMsg.style.display = 'block';
            return;
          }

          const totalAvailable = items.reduce(
            (sum, i) => sum + parseFloat(i.quantity || 0),
            0
          );

          this.tableBody.innerHTML = items
            .map((item, idx) => {
              const avail = parseFloat(item.quantity || 0);
              const isZero = avail <= 0;

              const unitText = isZero ? '--' : item.unit;
              const mixValue = isZero ? 0 : avail;
              const mixDisabled = isZero ? 'disabled' : '';

              return `
                  <tr data-id="${item.id}"
                      data-name="${item.product}"
                      data-unit="${item.unit}"
                      data-avail="${avail}">
                    <td class="text-center">${idx + 1}</td>
                    <td>${item.product}</td>
                    <td class="text-end fw-bold">${avail.toFixed(2)}</td>
                    <td class="text-center">${unitText}</td>
                    <td class="text-end">
                      <input type="number"
                             class="form-control form-control-sm text-end mix-input"
                             min="0"
                             step="0.1"
                             max="${avail}"
                             value="${mixValue}"
                             style="width: 100px;"
                             ${mixDisabled} />
                    </td>
                  </tr>`;
            })
            .join('');

          if (totalAvailable === 0) {
            this.noQtyMsg.style.display = 'block';
            this.btnMixSave.disabled = true;
          } else {
            this.noQtyMsg.style.display = 'none';
            this.btnMixSave.disabled = false;
          }
        } catch (e) {
          console.error('Failed to load raw stock:', e);
          this.tableBody.innerHTML =
            '<tr><td colspan="5" class="text-center text-danger">Error loading data</td></tr>';
          this.noQtyMsg.style.display = 'block';
          this.btnMixSave.disabled = true;
        }
      }

      async saveMixBatch() {
        try {
          await nativeApi.post('saveMilkMix');
          navigate();
        } catch (err) {
          console.error('Mix batch save failed:', err);
        }
      }
    },

    expose() {
      console.log('[MixMilk] expose called');
      window.MixMilkApp = new this.MixMilkApp();
      window.init_mix_milk = window.MixMilkApp.init.bind(window.MixMilkApp);
    }
  }
};
