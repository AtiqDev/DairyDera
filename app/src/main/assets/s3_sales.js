// sale.js
window.screenMap.sale = {
    template: 'sale.html',
    script: {
      // --- Customer invoice helper ---
      loadCustomerInvoice: async function(custId) {
        try {
          console.log("[Sale] loadCustomerInvoice start", { custId });

          const monthSelect = document.getElementById('custInvoiceMonth');
          const titleEl = document.getElementById('custInvTitle');
          const detailsEl = document.getElementById('custInvDetails');
          const headEl = document.getElementById('custInvHead');
          const bodyEl = document.getElementById('custInvBody');
          const payEl = document.getElementById('custInvPayment');
          const tableWrapper = document.querySelector('.table-scroll-wrapper');

          if (monthSelect && monthSelect.options.length === 0) {
            const monthNames = ['January','February','March','April','May','June','July','August','September','October','November','December'];
            monthSelect.innerHTML = monthNames.map((m,i)=>`<option value="${i+1}">${m}</option>`).join('');
            monthSelect.value = new Date().getMonth() + 1;
          }

          const monthId = parseInt(monthSelect?.value ?? (new Date().getMonth()+1), 10);
          const customerId = parseInt(custId, 10);

          titleEl.textContent = 'Loading invoice...';
          detailsEl.innerHTML = '';
          headEl.innerHTML = '';
          bodyEl.innerHTML = '';
          payEl.innerHTML = '';
          if (tableWrapper) tableWrapper.style.display = 'block';

          const exists = await nativeApi.call('isInvoiceExists', { customerId, monthId });
          if (exists !== "true") {
            titleEl.textContent = '';
            detailsEl.innerHTML = `<p style="font-size:1.5rem;" class="text-center text-danger fw-bold">No Sales Found</p>`;
            if (tableWrapper) tableWrapper.style.display = 'none';
            return;
          }

          let jsonString;
          try {
            jsonString = await nativeApi.call('getCustomerInvoiceDataString', { customerId, monthId });
          } catch (e) {
            jsonString = JSON.stringify({
              status: 'success',
              data: [[`Sample Invoice for ${monthId}`],['From DairyPOS'],['Bill To:', `Customer #${custId}`],[],['From', 'To', 'Days', 'Qty', 'Total Qty', 'amount'],['01-Nov', '05-Nov', '5', '7.5', '37.5', 'Rs 8,250.00'],[null, null, null, 'TOTAL', '37.5', 'Rs 8,250.00'],['Payment Method', 'Cash']]
            });
          }

          const response = JSON.parse(jsonString);
          if (!response || response.status !== 'success' || !Array.isArray(response.data)) {
            titleEl.textContent = 'No invoice data';
            return;
          }

          const rows = response.data;
          titleEl.textContent = rows[0][0] || 'Invoice';

          const isNumeric = (v) => /^-?\d+(\.\d+)?$/.test(String(v).trim());
          const isPaymentRow = (r) => r.some(c => typeof c === 'string' && /(bank|iban|payment|account)/i.test(c));

          rows.forEach((r,i) => {
            if (!Array.isArray(r)) return;
            if (!r.some(c => c && String(c).trim())) return;

            if (i < 4 && !isPaymentRow(r)) {
              detailsEl.insertAdjacentHTML('beforeend', `<p class="mb-1">${r.filter(c=>c).join(' ')}</p>`);
              return;
            }
            if (isPaymentRow(r)) {
              payEl.insertAdjacentHTML('beforeend', `<p class="mb-0">${r.filter(c=>c).join(' ')}</p>`);
              return;
            }

            const rowHtml = r.map(c => {
              const val = c || '';
              if (/^rs/i.test(val.trim()) || isNumeric(val)) return `<td class="text-end">${val}</td>`;
              return `<td>${val}</td>`;
            }).join('');
            bodyEl.insertAdjacentHTML('beforeend', `<tr>${rowHtml}</tr>`);
          });
        } catch (e) {
          console.error('❌ loadCustomerInvoice error:', e);
        }
      },

      SaleForm: {
        async init(params) {
          try {
            const custList = JSON.parse(await nativeApi.call('getCustomers'));
            const cust = custList.find(c => c.id == params.customerId) || {};
            const allSales = JSON.parse(await nativeApi.call('getSales'));
            const sale = params.id ? (allSales.find(s => s.id == params.id) || {}) : {};
            const statuses = JSON.parse(await nativeApi.call('getSaleStatus'));

            document.getElementById('saleFormTitle').textContent = `Sale for ${cust.name || ''}`;
            document.getElementById('resetSaleForm').onclick = () => this.init(params);

            const saleDateInput = document.getElementById('saleDate');
            saleDateInput.value = sale.saleDate ? sale.saleDate.slice(0, 10) : new Date().toISOString().slice(0, 10);

            document.getElementById('rate').value = sale.rate || cust.rate || 0;
            document.getElementById('qty').value = sale.quantity || cust.quantity || 0;
            document.getElementById('notes').value = sale.feedbackNotes || '';

            const sel = document.getElementById('status');
            sel.innerHTML = '';
            statuses.forEach(s => {
              const o = document.createElement('option');
              o.value = s.id;
              o.textContent = s.name;
              o.selected = sale.statusId ? s.id === sale.statusId : s.name.toLowerCase() === 'complete';
              sel.append(o);
            });

            setTimeout(() => this._loadInvoice(params.customerId), 500);
            document.getElementById('btnRefreshInvoice').onclick = () => this._loadInvoice(params.customerId);

            const saveBtn = document.getElementById('saveSaleForm');
            const newSave = saveBtn.cloneNode(true);
            saveBtn.replaceWith(newSave);
            newSave.addEventListener('click', async () => {
              try {
                const saleDataObj = {
                  id: sale.id || 0,
                  customerId: parseInt(params.customerId, 10),
                  saleDate: new Date(saleDateInput.value).toISOString(),
                  quantity: parseFloat(document.getElementById('qty').value),
                  rate: parseFloat(document.getElementById('rate').value),
                  statusId: parseInt(sel.value, 10),
                  feedbackNotes: document.getElementById('notes').value.trim(),
                  createDate: sale.createDate || new Date().toISOString(),
                  updateDate: new Date().toISOString()
                };
                const responseJsonString = await nativeApi.call('saveSale', saleDataObj);
                const statusObject = JSON.parse(responseJsonString);

                if (statusObject.saleIds && statusObject.saleIds.length > 0) {
                  alert(`✅ Sale saved successfully!`);
                  setTimeout(() => window.navigate(), 500);
                } else if (statusObject.error) {
                  alert(`⚠️ ERROR: ${statusObject.error}`);
                }
              } catch (err) {
                alert('Error while saving the sale.');
              }
            });

            // Back Sales
            const today = new Date().toISOString().split('T')[0];
            document.getElementById('fromDate').value = today;
            document.getElementById('toDate').value = today;
            document.getElementById('rangeQty').value = cust.quantity || 0;

            document.getElementById('backSalesBtn').onclick = () => { document.getElementById('backSalesPopup').style.display = 'flex'; };
            document.getElementById('closePopup').onclick = () => { document.getElementById('backSalesPopup').style.display = 'none'; };

            document.getElementById('saveBackSales').onclick = async () => {
              const start = new Date(document.getElementById('fromDate').value);
              const end = new Date(document.getElementById('toDate').value);
              const qty = parseFloat(document.getElementById('rangeQty').value);

              if (isNaN(qty) || !document.getElementById('fromDate').value || !document.getElementById('toDate').value) {
                alert('Invalid range or quantity.');
                return;
              }

              for (let d = new Date(start); d <= end; d.setDate(d.getDate() + 1)) {
                await nativeApi.post('saveSale', {
                  id: 0,
                  customerId: parseInt(params.customerId, 10),
                  saleDate: new Date(d).toISOString(),
                  quantity: qty,
                  rate: parseFloat(document.getElementById('rate').value),
                  statusId: parseInt(sel.value, 10),
                  feedbackNotes: document.getElementById('notes').value.trim(),
                  createDate: new Date().toISOString(),
                  updateDate: new Date().toISOString()
                });
              }
              document.getElementById('backSalesPopup').style.display = 'none';
              alert('✅ Back sales saved successfully!');
            };
          } catch (err) {
            console.error('❌ Init error:', err);
          }
        },
        _loadInvoice(customerId) {
          window.screenMap.sale.script.loadCustomerInvoice(customerId);
        }
      },
      expose() {
        window.init_sale = this.SaleForm.init.bind(this.SaleForm);
      }
    }
  };

