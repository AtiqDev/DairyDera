// s2_customers.js
window.screenMap.customers = {
    template: 'customers.html',
    script: {
      CustomersApp: class {
        constructor() {
          this.MODE_KEY = 'customers.mode';
          this.WALKIN_KEY = 'customers.showWalkin';
          this.refs = {};
          this.filtered = [];
          this.summaryMap = {};
        }

        async init(params = {}) {
          const backBtn       = document.getElementById('backCustList');
          const btnNew        = document.getElementById('btnNewCust');
          const searchInp     = document.getElementById('custSearch');
          const toggleWalkin  = document.getElementById('toggleWalkin');
          const radios        = document.querySelectorAll('input[name="mode"]');
          const custList      = document.getElementById('custList');
          const custContainer = document.getElementById('custContainer');
          const availMilkSpan = document.getElementById('availMilk');
          const soldMilkSpan  = document.getElementById('soldMilk');
          const btnExport     = document.getElementById('btnExportInvoices');

          this.refs = {
            searchInp, toggleWalkin, radios,
            custList, custContainer,
            availMilkSpan, soldMilkSpan
          };

          btnExport.onclick = () => { nativeApi.post('intentExportAllInvoices'); };

          const storedMode       = localStorage.getItem(this.MODE_KEY) || 'Sale';
          const storedShowWalkin = localStorage.getItem(this.WALKIN_KEY) === 'true';
          radios.forEach(r => r.checked = (r.value === storedMode));
          toggleWalkin.checked = storedShowWalkin;

          backBtn.onclick = () => {
            localStorage.removeItem(this.MODE_KEY);
            localStorage.removeItem(this.WALKIN_KEY);
            navigate();
          };
          btnNew.onclick = () => navigate('customer_form');
          searchInp.oninput = () => this.loadData();
          toggleWalkin.onchange = () => this.loadData();
          radios.forEach(r => r.onchange = () => this.loadData());

          this.loadData();
        }

        async loadData() {
          const { searchInp, toggleWalkin, availMilkSpan, soldMilkSpan } = this.refs;
          let availableLiters = 0;
          let soldToday = 0;

          try {
              const summaryRaw = await nativeApi.call('getMilkSummary');
              const [summary] = JSON.parse(summaryRaw);
              availableLiters = parseFloat(summary.availableLiters || 0);
              soldToday = parseFloat(summary.soldToday || 0);

              const fmt = val => isNaN(Number(val)) ? '--' : parseFloat(Number(val).toFixed(2)).toString();
              availMilkSpan.textContent = fmt(availableLiters);
              soldMilkSpan.textContent  = fmt(soldToday);
            } catch (e) {
              availMilkSpan.textContent = '--';
              soldMilkSpan.textContent  = '--';
            }

          const term = searchInp.value.trim();
          const classFilter = toggleWalkin.checked ? '2' : '1';
          try {
            const custRaw = await nativeApi.call('searchCustomers', {query: term, classIdStr: classFilter});
            this.filtered = JSON.parse(custRaw);
          } catch {
            this.filtered = [];
          }

          this.summaryMap = {};
          try {
              const summaryRaw = await nativeApi.call('getCustomerSalesSummariesThisMonth');
              const summaryData = JSON.parse(summaryRaw);
              summaryData.forEach(s => this.summaryMap[s.customerId] = s);
            } catch (e) {
              console.error('Error fetching customer sales summaries:', e);
            }

          this.renderList();
        }

        renderList() {
          const { toggleWalkin, custContainer, custList } = this.refs;
          const mode = document.querySelector('input[name="mode"]:checked').value;
          const showWalkin = toggleWalkin.checked;

          localStorage.setItem(this.MODE_KEY, mode);
          localStorage.setItem(this.WALKIN_KEY, showWalkin);

          custContainer.classList.remove('bg-info', 'bg-danger', 'bg-success', 'bg-opacity-25');
          if (showWalkin) custContainer.classList.add('bg-danger', 'bg-opacity-25');
          else if (mode === 'Payment') custContainer.classList.add('bg-success', 'bg-opacity-25');
          else custContainer.classList.add('bg-info', 'bg-opacity-25');

          custList.innerHTML = '';
          this.filtered.forEach(c => {
            const sum = this.summaryMap[c.id] || { salesCount: 0, qtySold: 0, amountTotal: 0 };
            const badgeCount = `<span class="badge bg-info me-1" title="Sales Count">${sum.salesCount}</span>`;
            const badgeQty   = `<span class="badge bg-success me-1" title="Qty Sold">${parseFloat(sum.qtySold || 0).toFixed(2)}</span>`;
            const badgeAmt   = `<span class="badge bg-warning" title="Total amount">${parseFloat(sum.amountTotal || 0).toFixed(2)}</span>`;

            const btn = document.createElement('button');
            btn.type = 'button';
            btn.className = 'list-group-item list-group-item-action d-flex justify-content-between align-items-center';
            btn.innerHTML = `<div><strong>${c.id} - ${c.name}</strong><small class="text-muted"> (${(c.quantity || 0)} Ltr @ ${(c.rate || 0)})</small></div><div>${badgeCount}${badgeQty}${badgeAmt}</div>`;

            btn.onclick = () => {
              if (mode === 'Edit') navigate('customer_form', { id: c.id });
              else if (mode === 'Payment') navigate('receive_payment', { customerId: c.id });
              else {
                const availMilk = parseFloat(document.getElementById('availMilk').textContent) || 0;
                if (availMilk <= 0) alert('Warning: No Milk available to sale.');
                else navigate('sale', { customerId: c.id });
              }
            };
            custList.appendChild(btn);
          });
        }
      },
      expose() {
        window.CustomersApp = new this.CustomersApp();
        window.init_customers = window.CustomersApp.init.bind(window.CustomersApp);
      }
    }
  };

