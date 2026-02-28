window.screenMap['pay_operational_liabilities'] = {
  template: 'pay_operational_liabilities.html',
  script: {

    // --- 1. DataBridge ---
    DataBridge: {
      async safeCall(fnName, defaultVal = null, payload = {}) {
        try {
          const raw = await nativeApi.call(fnName, payload);
          return raw ? JSON.parse(raw) : defaultVal;
        } catch (err) {
          console.error(`[PayOpLiab Bridge] ${fnName} error:`, err);
          return defaultVal;
        }
      },
      async getOperationalPayableBalances() {
        return this.safeCall('getOperationalPayableBalances', []);
      },
      async saveOperationalPayment(payload) {
        return this.safeCall('saveOperationalPayment', null, payload);
      }
    },

    // --- 2. PayOpApp ---
    PayOpApp: class {
      constructor() {
        this.selectedSubType = null;
        this.selectedBalance = 0;
        this.payables = [];
      }

      async init(params = {}) {
        const DB = window.screenMap['pay_operational_liabilities'].script.DataBridge;
        this.payables = await DB.getOperationalPayableBalances() || [];
        this._renderPayableList();

        document.getElementById('pol-date').value = new Date().toISOString().slice(0, 10);
        document.getElementById('pol-amount').addEventListener('input', () => this._validate());
        document.getElementById('pol-date').addEventListener('change',  () => this._validate());
        document.getElementById('pol-save-btn').addEventListener('click', () => this._handleSave());
      }

      _fmt(v) {
        return (Math.round((v || 0) * 100) / 100).toLocaleString('en-US', { minimumFractionDigits: 2 });
      }

      _renderPayableList() {
        const list = document.getElementById('pol-payables-list');
        if (!this.payables.length) {
          list.innerHTML = '<div class="text-center text-muted py-3">No payable accounts found.</div>';
          return;
        }

        list.innerHTML = this.payables.map(p => {
          const bal = parseFloat(p.balance) || 0;
          const balClass = bal > 0 ? 'positive' : 'zero';
          const subType = this._codeToSubType(p.code);
          return `
            <div class="pol-payable-row d-flex justify-content-between align-items-center px-3 py-3 border-bottom"
                 data-subtype="${subType}" data-balance="${bal}" onclick="window.PayOpLiabApp._onRowClick(this)">
              <div>
                <div class="fw-semibold">${subType}</div>
                <div class="text-muted small">${p.name} (${p.code})</div>
              </div>
              <div class="pol-balance ${balClass}">Rs ${this._fmt(bal)}</div>
            </div>`;
        }).join('');
      }

      _codeToSubType(code) {
        const map = { '2001': 'Rent', '2002': 'Wages', '2003': 'Electricity', '2004': 'Fuel' };
        return map[code] || code;
      }

      _onRowClick(el) {
        const subType = el.dataset.subtype;
        const balance = parseFloat(el.dataset.balance) || 0;

        this.selectedSubType = subType;
        this.selectedBalance  = balance;

        document.getElementById('pol-selected-label').textContent = subType;
        document.getElementById('pol-payment-form').style.display = 'block';
        document.getElementById('pol-footer').style.display = 'block';
        document.getElementById('pol-amount').value = '';
        document.getElementById('pol-notes').value  = '';
        this._validate();

        // scroll form into view
        document.getElementById('pol-payment-form').scrollIntoView({ behavior: 'smooth' });
      }

      _validate() {
        const amount = parseFloat(document.getElementById('pol-amount').value) || 0;
        const date   = document.getElementById('pol-date').value;
        const ok = this.selectedSubType && amount > 0 && date;
        document.getElementById('pol-save-btn').disabled = !ok;
        return ok;
      }

      async _handleSave() {
        if (!this._validate()) return;

        const amount = parseFloat(document.getElementById('pol-amount').value);
        const date   = document.getElementById('pol-date').value;
        const notes  = document.getElementById('pol-notes').value.trim();

        if (!confirm(`Pay Rs ${this._fmt(amount)} for ${this.selectedSubType}?`)) return;

        const saveBtn = document.getElementById('pol-save-btn');
        saveBtn.disabled = true;
        saveBtn.innerHTML = '<i class="fas fa-spinner fa-spin"></i> Saving…';

        const DB = window.screenMap['pay_operational_liabilities'].script.DataBridge;
        const res = await DB.saveOperationalPayment({
          subType: this.selectedSubType,
          amount,
          paymentDate: date,
          notes
        });

        if (res && res.id) {
          alert(`Payment #${res.id} saved.`);
          navigate('expense_stub');
        } else {
          alert('Save failed: ' + (res?.error || 'Unknown error'));
          saveBtn.disabled = false;
          saveBtn.innerHTML = '<i class="fas fa-hand-holding-usd"></i> Save Payment';
        }
      }
    },

    expose() {
      const app = new this.PayOpApp();
      window.PayOpLiabApp = app;
      window.init_pay_operational_liabilities = (p) => app.init(p);
    }
  }
};
