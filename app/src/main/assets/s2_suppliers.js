// s2_suppliers.js
window.screenMap.suppliers = {
    template: 'suppliers.html',
    script: {
      SuppliersApp: class {
        constructor() {
          this.MODE_KEY = 'suppliers.mode';
          this.btnAdd = null;
          this.searchInp = null;
          this.listDiv = null;
          this.modeRadios = null;
          this.filtered = [];
          this.summaryMap = {};
        }

        init(params = {}) {
          console.log('[Suppliers] init called', params);

          // cache DOM
          this.btnAdd = document.getElementById('btnAddSupplier');
          this.searchInp = document.getElementById('supplierSearch');
          this.listDiv = document.getElementById('supplierList');
          this.modeRadios = document.querySelectorAll('input[name="supMode"]');

          // navigation handlers
          this.btnAdd.onclick = () => navigate('supplier_form');

          // restore saved mode or default
          const savedMode = localStorage.getItem(this.MODE_KEY) || 'Purchase';
          this.modeRadios.forEach(r => {
            r.checked = r.value === savedMode;
            r.onchange = () => {
              localStorage.setItem(this.MODE_KEY, r.value);
              this.renderList();
            };
          });

          // search handler
          this.searchInp.addEventListener('input', () => this.loadData());

          // initial load
          this.loadData();
        }

        async loadData() {
          const term = this.searchInp.value.trim();
          try {
            if (term) {
              this.filtered = JSON.parse(await nativeApi.call('getSuppliersSearch', {term}));
            } else {
              this.filtered = JSON.parse(await nativeApi.call('getSuppliers'));
            }
          } catch (e) {
            console.error('Error fetching suppliers:', e);
            this.filtered = [];
          }

          // purchase summaries
          this.summaryMap = {};
          try {
              const summaryRaw = await nativeApi.call('getSupplierPurchaseSummariesThisMonth');
              const summaryData = JSON.parse(summaryRaw);
              summaryData.forEach(s => { this.summaryMap[s.supplierId] = s; });
            } catch (e) {
              console.error('Error fetching purchase summaries:', e);
            }

          this.renderList();
        }

        renderList() {
          const mode = document.querySelector('input[name="supMode"]:checked').value;
          this.listDiv.innerHTML = '';

          this.filtered.forEach(s => {
            const sum = this.summaryMap[s.id] || {
              purchaseCount: 0,
              qtyPurchased: 0,
              totalPrice: 0
            };

            const badgeCount = `<span class="badge bg-info me-1" title="Purchase Count">${sum.purchaseCount}</span>`;
            const badgeQty = `<span class="badge bg-success me-1" title="Qty Purchased">${Math.round(sum.qtyPurchased)}</span>`;
            const badgeAmt = `<span class="badge bg-warning" title="Total price">${Math.round(sum.totalPrice)}</span>`;

            const btn = document.createElement('button');
            btn.type = 'button';
            btn.className = 'list-group-item list-group-item-action d-flex justify-content-between align-items-center';
            btn.dataset.id = s.id;

            const leftHtml = `
              <div>
                <strong>${s.name}</strong>
              </div>`;

            btn.innerHTML = leftHtml + `<div>${badgeCount}${badgeQty}${badgeAmt}</div>`;

            btn.onclick = () => {
              if (mode === 'Edit') {
                navigate('supplier_form', { id: s.id });
              } else {
                navigate('purchase', { supplierId: s.id });
              }
            };

            this.listDiv.appendChild(btn);
          });
        }
      },

      expose() {
        console.log('[Suppliers] expose called');
        window.SuppliersApp = new this.SuppliersApp();
        window.init_suppliers = window.SuppliersApp.init.bind(window.SuppliersApp);
      }
    }
  };

