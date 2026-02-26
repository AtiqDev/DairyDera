window.screenMap.customer_form = {
  template: 'customer_form.html',
  script: {
    // --- DataBridge: safe wrappers around nativeApi ---
    DataBridge: {
      async _safe(action, def = [], payload = {}) {
        try {
          const raw = await nativeApi.call(action, payload);
          return raw ? JSON.parse(raw) : def;
        } catch (e) {
          console.error(`[Bridge] ${action} error`, e);
          return def;
        }
      },
      getCustomers() { return this._safe('getCustomers'); },
      getClasses() { return this._safe('getClasses'); },
      getCustomerLocations(id) { return this._safe('getCustomerLocations', [], { customerId: id }); },
      getCustomerPhotos(id) { return this._safe('getCustomerPhotos', [], { customerId: id }); },
      async isInvoiceExists(customerId, monthId) {
        try {
          const result = await nativeApi.call('isInvoiceExists', { customerId, monthId });
          return result === 'true';
        }
        catch { return false; }
      },
      async getCustomerInvoiceDataString(customerId, monthId) {
        return await nativeApi.call('getCustomerInvoiceDataString', { customerId, monthId });
      },
      saveCustomer(obj) { nativeApi.post('saveCustomer', obj); },
      intentGoogleMapRoute(id) { nativeApi.post('intentGoogleMapRoute', { customerId: id }); },
      intentCameraPhotoCapture(id) { nativeApi.post('intentCameraPhotoCapture', { customerId: id }); },
      intentGoogleMapRoute(lat, lon) { nativeApi.post('intentGoogleMapRoute', { lat, lon }); },
      updateLatLon(id, val) {
        const [lat, lon] = val.split(',').map(s => parseFloat(s.trim()));
        if (!isNaN(lat) && !isNaN(lon)) nativeApi.post('updateLatLon', { id, lat, lon });
      },
      updateMapUrl(id, val) { nativeApi.post('updateMapUrl', { id, url: val }); },
      deleteCustomerLocation(id) { nativeApi.post('deleteCustomerLocation', { id }); },
      deleteCustomerPhoto(id) { nativeApi.post('deleteCustomerPhoto', { id }); }
    },

    // --- Helper: render invoice section ---
    async loadCustomerInvoice(custId) {
      try {
        const monthSelect = document.getElementById('custInvoiceMonth');
        const titleEl = document.getElementById('custInvTitle');
        const detailsEl = document.getElementById('custInvDetails');
        const bodyEl = document.getElementById('custInvBody');
        const payEl = document.getElementById('custInvPayment');
        const tableWrapper = document.querySelector('.table-scroll-wrapper');

        if (monthSelect.options.length === 0) {
          const monthNames = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];
          monthSelect.innerHTML = monthNames.map((m, i) => `<option value="${i + 1}">${m}</option>`).join('');
          monthSelect.value = new Date().getMonth() + 1;
        }

        const DB = window.screenMap.customer_form.script.DataBridge;
        const monthId = parseInt(monthSelect.value, 10);

        titleEl.textContent = 'Loading invoice...';
        detailsEl.innerHTML = '';
        bodyEl.innerHTML = '';
        payEl.innerHTML = '';
        if (tableWrapper) tableWrapper.style.display = 'block';

        const exists = await DB.isInvoiceExists(+custId, monthId);
        if (!exists) {
          titleEl.textContent = '';
          detailsEl.innerHTML = `<p style="font-size:1.5rem;" class="text-center text-danger fw-bold">No Sales Found</p>`;
          if (tableWrapper) tableWrapper.style.display = 'none';
          return;
        }

        const jsonString = await DB.getCustomerInvoiceDataString(+custId, monthId);
        const response = jsonString ? JSON.parse(jsonString) : null;
        if (!response || response.status !== 'success' || !Array.isArray(response.data)) {
          titleEl.textContent = 'No invoice data';
          return;
        }

        const rows = response.data;
        titleEl.textContent = rows[0]?.[0] || 'Invoice';

        const isNumeric = (v) => /^-?\d+(\.\d+)?$/.test(String(v).trim());
        const isPaymentRow = (r) => r.some(c => typeof c === 'string' && /(bank|iban|payment|account)/i.test(c));

        rows.forEach((r, i) => {
          if (!Array.isArray(r) || !r.some(c => c && String(c).trim())) return;

          if (i < 4 && !isPaymentRow(r)) {
            detailsEl.insertAdjacentHTML('beforeend', `<p class="mb-1">${r.filter(c => c).join(' ')}</p>`);
            return;
          }
          if (isPaymentRow(r)) {
            payEl.insertAdjacentHTML('beforeend', `<p class="mb-0">${r.filter(c => c).join(' ')}</p>`);
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
        console.error('loadCustomerInvoice error:', e);
      }
    },

    // --- App: customer form lifecycle ---
    CustomerFormApp: class {
      constructor() { this.cust = {}; this.classes = []; }

      async init(params = {}) {
        const DB = window.screenMap.customer_form.script.DataBridge;
        const all = await DB.getCustomers();
        this.cust = params.id ? (all.find(x => x.id == params.id) || {}) : {};
        this.classes = await DB.getClasses();

        document.getElementById('custFormTitle').textContent = params.id ? 'Edit Customer' : 'New Customer';

        const fieldMap = ['name', 'address', 'phone', 'rate', 'quantity'];
        fieldMap.forEach(id => {
          const el = document.getElementById(id);
          el.value = this.cust[id] ?? el.value;
        });

        const sel = document.getElementById('class');
        sel.innerHTML = this.classes.map(cl => `<option value="${cl.id}" ${cl.id === this.cust.classId ? 'selected' : ''}>${cl.name}</option>`).join('');

        if (params.id) {
          this.loadExtras(params.id);
          setTimeout(() => window.screenMap.customer_form.script.loadCustomerInvoice(params.id), 500);
          document.getElementById('btnAddLocation').onclick = () => { DB.intentGoogleMapCapture(params.id); setTimeout(() => this.loadExtras(params.id), 1500); };
          document.getElementById('btnAddPhoto').onclick = () => { DB.intentCameraPhotoCapture(params.id); setTimeout(() => this.loadExtras(params.id), 1500); };
        } else {
          document.getElementById('btnAddLocation').style.display = 'none';
          document.getElementById('btnAddPhoto').style.display = 'none';
        }

        document.getElementById('saveCustForm').onclick = () => {
          DB.saveCustomer({
            id: this.cust.id || 0,
            name: document.getElementById('name').value.trim(),
            address: document.getElementById('address').value.trim(),
            phone: document.getElementById('phone').value.trim(),
            rate: parseInt(document.getElementById('rate').value) || 0,
            quantity: parseFloat(document.getElementById('quantity').value) || 0,
            classId: parseInt(sel.value),
            createDate: this.cust.createDate || new Date().toISOString(),
            updateDate: new Date().toISOString()
          });
          navigate();
        };
        document.getElementById('btnRefreshInvoice').onclick = () => window.screenMap.customer_form.script.loadCustomerInvoice(params.id);
      }

      async loadExtras(custId) {
        const DB = window.screenMap.customer_form.script.DataBridge;
        const locContainer = document.getElementById('locationContainer');
        const photoContainer = document.getElementById('photoContainer');

        const locs = await DB.getCustomerLocations(custId);
        locContainer.innerHTML = locs.length ? locs.map(l => `
            <div class="border rounded p-2 mb-2">
              <div class="d-flex justify-content-between align-items-center">
                <div class="flex-grow-1 me-2">
                  <input type="text" value="${(+l.latitude).toFixed(5)}, ${(+l.longitude).toFixed(5)}" class="form-control form-control-sm mb-1" onchange="screenMap.customer_form.script.DataBridge.updateLatLon(${l.id}, this.value)">
                  <input type="text" value="${l.mapUrl || ''}" placeholder="Map URL..." class="form-control form-control-sm" onchange="screenMap.customer_form.script.DataBridge.updateMapUrl(${l.id}, this.value)">
                </div>
                <div class="d-flex flex-column gap-1">
                  <button class="btn btn-sm btn-outline-success" onclick="screenMap.customer_form.script.DataBridge.intentGoogleMapRoute(${l.latitude},${l.longitude})"><i class="fas fa-route"></i></button>
                  <button class="btn btn-sm btn-outline-danger" onclick="if(confirm('Delete?')){screenMap.customer_form.script.DataBridge.deleteCustomerLocation(${l.id});setTimeout(() => window.CustomersFormApp.loadExtras(${custId}), 300);}"><i class="fas fa-trash"></i></button>
                </div>
              </div>
            </div>`).join('') : '<div class="text-center text-secondary">No locations</div>';

        const photos = await DB.getCustomerPhotos(custId);
        photoContainer.innerHTML = photos.length ? photos.slice(0, 4).map(p => `
            <div class="position-relative mb-3">
              <img src="data:image/jpeg;base64,${p.base64}" class="w-100 rounded border" style="max-height:220px; object-fit:cover;">
              <button class="btn btn-sm btn-outline-danger position-absolute top-0 end-0 m-2" onclick="if(confirm('Delete?')){screenMap.customer_form.script.DataBridge.deleteCustomerPhoto(${p.id});setTimeout(() => window.CustomersFormApp.loadExtras(${custId}), 300);}"><i class="fas fa-trash"></i></button>
            </div>`).join('') : '<div class="text-center text-secondary">No photos</div>';
      }
    },
    expose() {
      window.CustomersFormApp = new this.CustomerFormApp();
      window.init_customer_form = window.CustomersFormApp.init.bind(window.CustomersFormApp);
    }
  }
};

