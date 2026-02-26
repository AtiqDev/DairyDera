window.screenMap.invoices = {
  template: 'invoices.html',
  script: {
    InvoicesApp: class {
      constructor() {
        this.listEl = null;
        this.radios = null;
        this.currentStatus = 'Open';
      }

      init(params = {}) {
        console.log('[Invoices] init called', params);

        const backBtn = document.getElementById('backInvoices');
        this.listEl = document.getElementById('invoiceList');
        this.radios = document.querySelectorAll('input[name="invStatus"]');

        backBtn.onclick = () => navigate();

        this.radios.forEach(r => {
          r.addEventListener('change', () => {
            this.currentStatus = document.querySelector('input[name="invStatus"]:checked').value;
            this.loadData();
          });
        });

        this.loadData();
      }

      async loadData() {
        try {
          let raw = '[]';
          if (this.currentStatus === 'Paid') {
            raw = await nativeApi.call('getPaidInvoices');
          } else { // 'Open'
            raw = await nativeApi.call('getOpenInvoices');
          }
          const data = JSON.parse(raw) || [];
          this.renderList(data);
        } catch (err) {
          console.error('getInvoices error:', err);
          this.listEl.innerHTML = '<div class="text-center text-danger py-3">Error loading invoices</div>';
        }
      }

      renderList(rows) {
        this.listEl.innerHTML = '';

        if (!rows.length) {
          const empty = document.createElement('div');
          empty.className = 'text-center text-muted py-3';
          empty.textContent = 'No invoices found.';
          this.listEl.appendChild(empty);
          return;
        }

        rows.forEach(inv => {
          const total = inv.total || 0;
          const paid = inv.totalPaid || 0;
          const balance = total - paid;

          const btn = document.createElement('button');
          btn.type = 'button';
          btn.className = 'list-group-item list-group-item-action d-flex justify-content-between align-items-center';
          btn.dataset.id = inv.id;

          const left = `
              <div>
                <strong>${inv.customerName || '—'}</strong>
                <small class="text-muted"> (${inv.invoiceDate?.slice(0, 10) || ''})</small>
              </div>`;

          const right = `
              <div class="d-flex align-items-center gap-2">
                <span class="badge bg-${inv.status === 'Paid' ? 'success' : 'info'}">${inv.status}</span>
                <span class="badge bg-warning">$${total.toFixed(2)}</span>
                <span class="badge bg-${balance > 0 ? 'danger' : 'secondary'}">Bal: $${balance.toFixed(2)}</span>
              </div>`;

          btn.innerHTML = left + right;
          btn.onclick = () => navigate('invoice', { id: inv.id });
          this.listEl.appendChild(btn);
        });
      }
    },

    expose() {
      console.log('[Invoices] expose called');
      window.InvoicesApp = new this.InvoicesApp();
      window.init_invoices = window.InvoicesApp.init.bind(window.InvoicesApp);
    }
  }
};
