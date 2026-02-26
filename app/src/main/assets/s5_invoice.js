window.screenMap.invoice = {
  template: 'invoice.html',
  script: {
    // --- Helper ---
    fmtMoney(v) {
      try { return (Math.round((v || 0) * 100) / 100).toLocaleString(); }
      catch (e) { return v; }
    },

    InvoiceApp: class {
      constructor() {
        this.customerId = 0;
      }

      renderHeader(h) {
        document.getElementById('customerName').textContent = h.customerName || '—';
        document.getElementById('invoiceDate').textContent = (h.invoiceDate || '').slice(0, 10) || '—';
        document.getElementById('invoiceStatus').textContent = h.status || '—';
        document.getElementById('invoiceTotal').textContent = window.screenMap.invoice.script.fmtMoney(h.total || 0.0);
        document.getElementById('invoicePaid').textContent = window.screenMap.invoice.script.fmtMoney(h.paid || 0.0);
        document.getElementById('invoiceBalance').textContent = window.screenMap.invoice.script.fmtMoney(h.balance || 0.0);
        document.getElementById('invoiceNotes').textContent = h.notes || '—';
        document.getElementById('invoiceTitle').textContent = `Invoice #${h.invoiceId || ''}`;

        this.customerId = h.customerId || 0;
      }

      renderLines(lines) {
        const tbody = document.querySelector('#invoiceTable tbody');
        tbody.innerHTML = '';
        if (!lines || !lines.length) {
          const tr = document.createElement('tr');
          tr.innerHTML = `<td colspan="5" class="text-center text-muted py-3">No detail lines</td>`;
          tbody.appendChild(tr);
          return;
        }

        lines.forEach(r => {
          const tr = document.createElement('tr');
          tr.innerHTML = `
              <td style="border:1px solid #dee2e6;padding:0.5rem;">${r.fromDay}</td>
              <td style="border:1px solid #dee2e6;padding:0.5rem;">${r.toDay}</td>
              <td style="border:1px solid #dee2e6;padding:0.5rem;">${r.days}</td>
              <td style="border:1px solid #dee2e6;padding:0.5rem;text-align:right;">${(Math.round((r.quantity || 0) * 100) / 100).toLocaleString()}</td>
              <td style="border:1px solid #dee2e6;padding:0.5rem;text-align:right;">${window.screenMap.invoice.script.fmtMoney(r.totalAmt)}</td>
            `;
          tbody.appendChild(tr);
        });
      }

      async load(invoiceId) {
        try {
          if (!invoiceId) {
            console.error('Invoice id missing');
            return;
          }
          const raw = await nativeApi.call('getInvoiceDetails', { invoiceId });
          const data = raw ? JSON.parse(raw) : null;
          if (!data || data.error) {
            console.error('getInvoice error:', data?.error);
            document.querySelector('#invoiceTable tbody').innerHTML =
              `<tr><td colspan="5" class="text-center text-danger py-3">Error: ${data?.error || 'Not found'}</td></tr>`;
            return;
          }
          this.renderHeader(data.header || {});
          this.renderLines(data.lines || []);
        } catch (err) {
          console.error('load invoice error:', err);
        }
      }

      init(params = {}) {
        try {
          const btnClose = document.getElementById('btnClose');
          const btnReceive = document.getElementById('btnReceivePayment');
          const backBtn = document.getElementById('backInvoice');

          btnClose.onclick = backBtn.onclick = () => navigate();

          btnReceive.onclick = () => {
            if (!this.customerId) {
              alert('Customer not found.');
              return;
            }
            navigate('receive_payment', { customerId: this.customerId });
          };

          const invoiceId = +params.id || +params.invoiceId || 0;
          this.load(invoiceId);
        } catch (err) {
          console.error('init_invoice outer error:', err);
        }
      }
    },

    expose() {
      console.log('[Invoice] expose called');
      window.InvoiceApp = new this.InvoiceApp();
      window.init_invoice = window.InvoiceApp.init.bind(window.InvoiceApp);
    }
  }
};
