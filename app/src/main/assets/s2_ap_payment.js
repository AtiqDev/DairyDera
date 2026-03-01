window.screenMap.ap_payment = {
    template: 'ap_payment.html',
    script: {

      // --- 1. DataBridge ---
      DataBridge: {
        async safeCall(fnName, defaultVal = null, payload = {}) {
          try {
            const raw = await nativeApi.call(fnName, payload);
            return raw ? JSON.parse(raw) : defaultVal;
          } catch (err) {
            console.error(`[ApPayment Bridge] ${fnName} error:`, err);
            return defaultVal;
          }
        },
        async getSuppliersWithOpenBalance() { return this.safeCall('getSuppliersWithOpenBalance', []); },
        async getApPaymentMethods()         { return this.safeCall('getApPaymentMethods', []); },
        async savePayablePayment(payload)   { return this.safeCall('savePayablePayment', null, payload); }
      },

      // --- 2. ApPaymentApp ---
      ApPaymentApp: class {
        constructor() {
          this.openBalance    = 0;
          this.paymentMethods = [];
          this.refs           = {};
        }

        async init(params = {}) {
          this.refs = {
            supplierSel:    document.getElementById('supplierSelect'),
            balanceCard:    document.getElementById('balanceCard'),
            paymentCard:    document.getElementById('paymentCard'),
            balanceDisplay: document.getElementById('openBalanceDisplay'),
            methodSel:      document.getElementById('methodSelect'),
            amountIn:       document.getElementById('paymentAmount'),
            dateIn:         document.getElementById('paymentDate'),
            notesIn:        document.getElementById('paymentNotes'),
            saveBtn:        document.getElementById('savePaymentBtn')
          };

          const DB = window.screenMap.ap_payment.script.DataBridge;

          const [suppliers, methods] = await Promise.all([
            DB.getSuppliersWithOpenBalance(),
            DB.getApPaymentMethods()
          ]);

          this.paymentMethods = methods || [];

          // Populate supplier dropdown — each option shows name and outstanding balance
          if (!suppliers || !suppliers.length) {
            this.refs.supplierSel.innerHTML = '<option value="">No outstanding payables</option>';
            this.refs.supplierSel.disabled = true;
            return;
          }

          this.refs.supplierSel.innerHTML =
            '<option value="">Select Supplier\u2026</option>' +
            suppliers.map(s => {
              const balText = 'Rs ' + this._fmt(s.openBalance);
              return `<option value="${s.id}" data-balance="${s.openBalance}">${s.name} \u2014 ${balText}</option>`;
            }).join('');

          // Populate method dropdown, default Cash
          this._populateMethodSelect();

          if (params.supplierId) {
            this.refs.supplierSel.value = params.supplierId;
            this._onSupplierChange();
          }

          this.refs.dateIn.value = new Date().toISOString().slice(0, 10);
          this._bindEvents();
        }

        _populateMethodSelect() {
          const cashMethod = this.paymentMethods.find(m => m.name === 'Cash');
          const defaultId  = cashMethod ? cashMethod.id : (this.paymentMethods[0]?.id ?? '');

          this.refs.methodSel.innerHTML =
            this.paymentMethods.map(m =>
              `<option value="${m.id}" data-name="${m.name}"${m.id === defaultId ? ' selected' : ''}>${m.name}</option>`
            ).join('');
        }

        _bindEvents() {
          this.refs.supplierSel.addEventListener('change', () => this._onSupplierChange());
          this.refs.methodSel.addEventListener('change',   () => this._validate());
          this.refs.amountIn.addEventListener('input',     () => this._validate());
          this.refs.dateIn.addEventListener('change',      () => this._validate());
          this.refs.saveBtn.addEventListener('click',      () => this.handleSave());
        }

        _onSupplierChange() {
          const opt = this.refs.supplierSel.selectedOptions[0];
          if (!opt || !opt.value) {
            this.refs.balanceCard.style.display = 'none';
            this.refs.paymentCard.style.display = 'none';
            this.openBalance = 0;
            this._validate();
            return;
          }

          this.openBalance = parseFloat(opt.dataset.balance) || 0;
          this.refs.balanceDisplay.textContent = 'Rs ' + this._fmt(this.openBalance);
          this.refs.balanceCard.style.display = 'block';
          this.refs.paymentCard.style.display = 'block';
          this._validate();
        }

        _validate() {
          const supplierId = +this.refs.supplierSel.value;
          const methodId   = +this.refs.methodSel.value;
          const amount     = parseFloat(this.refs.amountIn.value) || 0;
          const ok = supplierId > 0
                  && methodId   > 0
                  && amount     > 0
                  && amount <= this.openBalance + 0.005;
          this.refs.saveBtn.disabled = !ok;
          return ok;
        }

        async handleSave() {
          if (!this._validate()) return;

          const supplierId      = +this.refs.supplierSel.value;
          const amount          = parseFloat(this.refs.amountIn.value);
          const date            = this.refs.dateIn.value;
          const notes           = this.refs.notesIn.value.trim();
          const paymentMethodId = +this.refs.methodSel.value;
          const methodName      = this.refs.methodSel.selectedOptions[0]?.dataset.name || '';
          const supplierName    = this.refs.supplierSel.selectedOptions[0]?.textContent.split(' \u2014 ')[0] || '';

          if (!confirm(`Pay Rs ${this._fmt(amount)} to ${supplierName} via ${methodName}?`)) return;

          this.refs.saveBtn.disabled = true;
          this.refs.saveBtn.innerHTML = '<i class="fas fa-spinner fa-spin"></i> Saving\u2026';

          const DB = window.screenMap.ap_payment.script.DataBridge;
          const res = await DB.savePayablePayment({
            supplierId,
            amount,
            paymentMethodId,
            paymentDate: date,
            notes
          });

          if (res && (res.isGood || res.id > 0)) {
            alert(`Payment #${res.id} saved.`);
            navigate();
          } else {
            alert('Save failed: ' + (res?.error || 'Unknown error'));
            this.refs.savesos
            Btn.disabled = false;
            this.refs.saveBtn.innerHTML = '<i class="fas fa-save"></i> Save Payment';
          }
        }

        _fmt(v) {
          return (Math.round((v || 0) * 100) / 100).toLocaleString('en-US', { minimumFractionDigits: 2 });
        }
      },

      expose() {
        window.ApPaymentApp = new this.ApPaymentApp();
        window.init_ap_payment = window.ApPaymentApp.init.bind(window.ApPaymentApp);
      }
    }
  };
