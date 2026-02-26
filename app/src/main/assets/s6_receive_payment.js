window.screenMap.receive_payment = {
    template: 'receive_payment.html',
    script: {
      fmtMoney(v) {
        try {
          return (Math.round((v || 0) * 100) / 100).toLocaleString(undefined, { minimumFractionalDigits: 2 });
        } catch (e) {
          return v;
        }
      },

      ReceivePaymentApp: class {
        constructor() {
          this.refs = {};
          this.customerId = 0;
          this.customerName = '';
          this.previousRemaining = 0;
          this.openInvoices = [];
        }

        async init(params = {}) {
          try {
            this.customerId = +params.customerId || 0;
            if (!this.customerId) {
              alert('Invalid customer');
              navigate();
              return;
            }

            this.refs = {
              form: document.getElementById('paymentForm'),
              amountIn: document.getElementById('paymentAmount'),
              notesIn: document.getElementById('notes'),
              customerEl: document.getElementById('customerName'),
              invoicesContainer: document.getElementById('openInvoices'),
              totalBalanceEl: document.getElementById('totalBalance'),
              receiveBtn: document.getElementById('receiveBtn'),
              previousBanner: document.getElementById('previousBalanceBanner'),
              previousText: document.getElementById('previousBalanceText')
            };

            await this.loadCustomer();
            await this.loadOpenInvoices();
            this.bindEvents();
          } catch (err) {
            console.error('init_receive_payment error:', err);
          }
        }

        async loadCustomer() {
          try {
            const customers = JSON.parse(await nativeApi.call('getCustomers'));
            const cust = customers.find(c => c.id === this.customerId);
            const name = cust?.name || 'Unknown Customer';
            this.refs.customerEl.textContent = name;
            this.customerName = name;
          } catch (err) {
            console.error('loadCustomer error:', err);
            this.refs.customerEl.textContent = '—';
          }
        }

        async loadOpenInvoices() {
          try {
            const rawInvoices = await nativeApi.call('getCustomerOpenInvoices', {customerId: this.customerId});
            const invoices = rawInvoices ? JSON.parse(rawInvoices) : [];
            if (!Array.isArray(invoices)) throw new Error('Invalid data');

            const rawPayments = await nativeApi.call('getCustomerOpenPayments', {customerId: this.customerId});
            const openPayments = rawPayments ? JSON.parse(rawPayments) : [];
            this.previousRemaining = openPayments.reduce((sum, p) => sum + (p.remaining || 0), 0);

            if (this.previousRemaining > 0.01) {
              this.refs.previousText.textContent = `Rs ${window.screenMap.receive_payment.script.fmtMoney(this.previousRemaining)} from previous payments will be applied automatically.`;
              this.refs.previousBanner.classList.remove('d-none');
            } else {
              this.refs.previousBanner.classList.add('d-none');
            }

            this.openInvoices = invoices;

            if (!invoices.length && this.previousRemaining <= 0.01) {
              this.refs.invoicesContainer.innerHTML = '<div class="p-3 text-center text-success">No open invoices</div>';
              this.refs.totalBalanceEl.textContent = 'Balance: Rs 0.00';
              return;
            }

            let totalBal = 0;
            const html = invoices.map(inv => {
              const bal = inv.balance || 0;
              totalBal += bal;
              const statusClass = bal > 0.01 ? 'balance-positive' : 'balance-zero';
              return `
                <div class="invoice-item d-flex justify-content-between align-items-center p-2 border-bottom">
                  <div>
                    <div class="fw-semibold">#${inv.id} - ${this.formatDate(inv.invoiceDate)}</div>
                    <small class="text-muted">Total: Rs ${window.screenMap.receive_payment.script.fmtMoney(inv.total)}</small>
                  </div>
                  <div class="text-end">
                    <div class="${statusClass}">Rs ${window.screenMap.receive_payment.script.fmtMoney(bal)}</div>
                  </div>
                </div>
              `;
            }).join('');

            this.refs.invoicesContainer.innerHTML = html;
            this.refs.totalBalanceEl.textContent = `Balance: Rs ${window.screenMap.receive_payment.script.fmtMoney(totalBal)}`;
          } catch (err) {
            console.error('loadOpenInvoices error:', err);
            this.refs.invoicesContainer.innerHTML = '<div class="p-3 text-center text-danger">Failed to load invoices</div>';
            this.refs.totalBalanceEl.textContent = 'Balance: —';
          }
        }

        formatDate(iso) {
          try { return new Date(iso).toLocaleDateString(); }
          catch { return iso || '—'; }
        }

        bindEvents() {
          const { form } = this.refs;

          form.onsubmit = async e => {
            e.preventDefault();
            form.classList.add('was-validated');
            if (!form.checkValidity()) return;

            const amount = parseFloat(this.refs.amountIn.value);
            if (isNaN(amount) || amount <= 0) {
              alert('Please enter a valid payment amount.');
              return;
            }

            const totalAvailable = amount + this.previousRemaining;
            if (!confirm(`Receive Rs ${window.screenMap.receive_payment.script.fmtMoney(amount)} from ${this.customerName}?\n(Total available: Rs ${window.screenMap.receive_payment.script.fmtMoney(totalAvailable)})`)) return;

            this.setProcessing(true);

            try {
              const resRaw = await nativeApi.call('receivePayment', {
                customerId: this.customerId,
                amount: amount,
                notes: this.refs.notesIn.value.trim()
              });
              const res = typeof resRaw === 'string' ? JSON.parse(resRaw) : resRaw;
              if (res.success) {
                alert(`Payment #${res.paymentId} received and applied!`);
                navigate();
              } else {
                alert('Payment failed: ' + (res.error || 'Unknown error'));
                this.setProcessing(false);
              }
            } catch (err) {
              console.error('receivePayment call error:', err);
              alert('Failed to send payment.');
              this.setProcessing(false);
            }
          };
        }

        setProcessing(processing) {
          const btn = this.refs.receiveBtn;
          if (processing) {
            btn.disabled = true;
            btn.innerHTML = '<i class="fas fa-spinner fa-spin"></i> Processing...';
          } else {
            btn.disabled = false;
            btn.innerHTML = '<i class="fas fa-money-bill-wave"></i> Receive Payment';
          }
        }
      },

      expose() {
        window.ReceivePaymentApp = new this.ReceivePaymentApp();
        window.init_receive_payment = window.ReceivePaymentApp.init.bind(window.ReceivePaymentApp);
      }
    }
  };

