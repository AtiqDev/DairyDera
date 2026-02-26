window.screenMap.supplier_form = {
    template: 'supplier_form.html',
    script: {
      // --- DataBridge: safe nativeApi calls ---
      DataBridge: {
        async safeCall(fnName, defaultVal = [], payload = {}) {
          try {
            const raw = await nativeApi.call(fnName, payload);
            return raw ? JSON.parse(raw) : defaultVal;
          } catch (err) {
            console.error(`[Bridge] ${fnName} failed`, err);
          }
          return defaultVal;
        },
        async getSuppliers() { return this.safeCall('getSuppliers'); },
        async getSupplierItems(id) { return this.safeCall('getSupplierItems', [], { supplierId: id }); },
        async getAllProducts() { return this.safeCall('getAllProducts'); },
        async getUOMList() { return this.safeCall('getAllUnits'); },
        async getConversion(fromUomId, toUomId) { return this.safeCall('getConversion', [], { fromUnit: fromUomId, toUnit: toUomId }); },
        async saveSupplier(payload) { return this.safeCall('saveSupplier', null, payload); },
        async saveSupplierItems(payload) { return nativeApi.post('saveSupplierItems', payload); },
        async deleteSupplier(id) { return nativeApi.post('deleteSupplier', { id }); }
      },

      // --- CardManager: supplier item cards ---
      CardManager: class {
        constructor(containerId, editCallback) {
          this.container = document.getElementById(containerId);
          this.editCallback = editCallback;
          this.container.addEventListener('click', this._handleClick.bind(this));
        }
        _handleClick(ev) {
          const card = ev.target.closest('.item-card');
          if (!card) return;
          if (ev.target.closest('.remove-line')) {
            ev.stopPropagation();
            card.remove();
            this._reindex();
          } else {
            this.clearSelection();
            card.classList.add('selected');
            this.editCallback(card);
          }
        }
        _reindex() {
          Array.from(this.container.children).forEach((c, i) => (c.dataset.rowIndex = i));
        }
        clearSelection() {
          Array.from(this.container.children).forEach(c => c.classList.remove('selected'));
        }
        addOrUpdateCard(item, cardToUpdate = null) {
          const title = item.productName || item.itemName || 'Unknown';
          const uom = item.uomName || '';
          const price = parseFloat(item.pricePerUom || 0).toFixed(2);

          if (cardToUpdate) {
            cardToUpdate.querySelector('.item-name').textContent = title;
            cardToUpdate.querySelector('.item-meta').textContent = `UOM: ${uom} • price: ${price}`;
            cardToUpdate.classList.remove('selected');
            cardToUpdate.dataset.productId = item.productId;
            cardToUpdate.dataset.uomId = item.uomId;
            cardToUpdate.dataset.price = item.pricePerUom;
          } else {
            const card = document.createElement('div');
            card.className = 'item-card';
            card.innerHTML = `
              <div class="item-details">
                <div class="item-name">${title}</div>
                <div class="item-meta">UOM: ${uom} • price: ${price}</div>
              </div>
              <div class="item-actions">
                <button type="button" class="btn btn-sm btn-outline-danger remove-line" aria-label="Remove">
                  <i class="fas fa-trash"></i>
                </button>
              </div>
            `;
            card.dataset.productId = item.productId;
            card.dataset.uomId = item.uomId;
            card.dataset.price = item.pricePerUom;
            this.container.appendChild(card);
          }
          this._reindex();
        }
        getCardData() {
          return Array.from(this.container.querySelectorAll('.item-card')).map(c => ({
            productId: +c.dataset.productId,
            uomId: +c.dataset.uomId,
            pricePerUom: +c.dataset.price
          }));
        }
      },

      // --- SupplierFormApp ---
      SupplierFormApp: class {
        constructor() {
          this.cardManager = null;
          this.editCardRef = null;
          this.products = [];
          this.uoms = [];
        }

        async init(params = {}) {
          this.supplierId = params.id || 0;
          this.form = document.getElementById('supplier-form');
          this.saveBtn = document.getElementById('saveSupForm');
          this.addBtn = document.getElementById('addItemRow');

          this.popup = document.getElementById('addItemPopup');
          this.popupTitle = document.getElementById('popupTitle');
          this.popupProduct = document.getElementById('popupProductSelect');
          this.popupUom = document.getElementById('popupUomSelect');
          this.popupPrice = document.getElementById('popupPrice');
          this.popupBasePrice = document.getElementById('popupBasePrice');
          this.popupCancel = document.getElementById('popupCancel');
          this.popupSave = document.getElementById('popupSave');

          this.cardManager = new window.screenMap.supplier_form.script.CardManager(
            'staticItemsGridInner',
            this.openEditPopup.bind(this)
          );

          const DB = window.screenMap.supplier_form.script.DataBridge;
          this.products = await DB.getAllProducts();
          this.uoms = await DB.getUOMList();

          const suppliers = await DB.getSuppliers();
          const isEdit = !!params.id;
          const sup = isEdit ? (suppliers.find(s => s.id == params.id) || {}) : {};
          document.getElementById('supFormTitle').textContent = isEdit ? 'Edit Supplier' : 'New Supplier';
          document.getElementById('name').value = sup.name || '';
          document.getElementById('address').value = sup.address || '';
          document.getElementById('phone').value = sup.phone || '';

          if (isEdit) {
            const items = await DB.getSupplierItems(params.id) || [];
            items.forEach(it => {
              const prod = this.products.find(p => p.id == it.productId);
              const uom = this.uoms.find(u => u.id == it.uomId);
              this.cardManager.addOrUpdateCard({
                productId: it.productId,
                uomId: it.uomId,
                pricePerUom: it.pricePerUom,
                productName: prod?.name || '',
                uomName: uom?.name || ''
              });
            });
          }

          this.addBtn.onclick = () => this.openAddPopup();
          this.popupCancel.onclick = () => this.closePopup();
          this.popupSave.onclick = () => this.handlePopupSave();
          this.form.onsubmit = e => this.handleFormSubmit(e);
          document.getElementById('name').oninput = () => this._validateForm();

          this.popupProduct.onchange = () => this._updateBasePriceDisplay();
          this.popupUom.onchange = () => this._updateBasePriceDisplay();
          this.popupPrice.oninput = () => this._updateBasePriceDisplay();

          this._validateForm();
        }

        _populateProductOptions(selectedId) {
          let html = '<option value="">Select Product</option>';
          this.products.forEach(p => {
            const sel = p.id == selectedId ? ' selected' : '';
            html += `<option value="${p.id}" data-base-uom="${p.baseUomId}"${sel}>${p.name}</option>`;
          });
          this.popupProduct.innerHTML = html;
        }
        _populateUomOptions(selectedId) {
          let html = '<option value="">Select UOM</option>';
          this.uoms.forEach(u => {
            const sel = u.id == selectedId ? ' selected' : '';
            html += `<option value="${u.id}"${sel}>${u.name}</option>`;
          });
          this.popupUom.innerHTML = html;
        }
        async _getConversionFactor(fromUOM, toUOM) {
          try {
            if (!fromUOM || !toUOM || fromUOM == toUOM) return 1;
            const DB = window.screenMap.supplier_form.script.DataBridge;
            const fwd = await DB.getConversion(fromUOM, toUOM);
            if (Array.isArray(fwd) && fwd.length) return fwd[0].conversionFactor;
            const rev = await DB.getConversion(toUOM, fromUOM);
            return Array.isArray(rev) && rev.length ? 1 / rev[0].conversionFactor : 1;
          } catch (err) {
            return 1;
          }
        }
        async _updateBasePriceDisplay() {
          const prodOpt = this.popupProduct.selectedOptions[0];
          const baseUomId = prodOpt ? +prodOpt.dataset.baseUom : null;
          const uomId = +this.popupUom.value;
          const price = parseFloat(this.popupPrice.value) || 0;
          if (!baseUomId || !uomId) {
            if (this.popupBasePrice) this.popupBasePrice.textContent = '0.00';
            return;
          }
          const factor = await this._getConversionFactor(uomId, baseUomId);
          if (this.popupBasePrice) this.popupBasePrice.textContent = (price / factor).toFixed(2);
        }

        openAddPopup() {
          this.editCardRef = null;
          this.popupTitle.textContent = 'Add Offered Item';
          this._populateProductOptions();
          this._populateUomOptions();
          this.popupPrice.value = '0';
          if (this.popupBasePrice) this.popupBasePrice.textContent = '0.00';
          this.popup.style.display = 'flex';
        }
        openEditPopup(card) {
          this.editCardRef = card;
          this.popupTitle.textContent = 'Edit Offered Item';
          const prodId = +card.dataset.productId;
          const uomId = +card.dataset.uomId;
          const price = +card.dataset.price;
          this._populateProductOptions(prodId);
          this._populateUomOptions(uomId);
          this.popupPrice.value = isNaN(price) ? '0' : price;
          this._updateBasePriceDisplay();
          this.popup.style.display = 'flex';
        }
        closePopup() {
          this.popup.style.display = 'none';
          this.editCardRef = null;
          this.cardManager.clearSelection();
        }

        handlePopupSave() {
          const prodId = +this.popupProduct.value;
          const uomId = +this.popupUom.value;
          const price = parseFloat(this.popupPrice.value) || 0;
          if (!prodId || !uomId) return alert('Fill correctly.');
          const prod = this.products.find(p => p.id === prodId);
          const uom = this.uoms.find(u => u.id === uomId);

          const item = {
            productId: prodId,
            uomId: uomId,
            pricePerUom: price,
            productName: prod?.name || '',
            uomName: uom?.name || ''
          };
          if (this.editCardRef) this.cardManager.addOrUpdateCard(item, this.editCardRef);
          else this.cardManager.addOrUpdateCard(item);
          this.closePopup();
          this._validateForm();
        }

        async handleFormSubmit(e) {
          e.preventDefault();
          if (!this._validateForm()) return;
          try {
            const DB = window.screenMap.supplier_form.script.DataBridge;
            const supObj = {
              id: this.supplierId || 0,
              name: document.getElementById('name').value.trim(),
              address: document.getElementById('address').value.trim(),
              phone: document.getElementById('phone').value.trim(),
              createDate: new Date().toISOString(),
              updateDate: new Date().toISOString()
            };
            const savedRaw = await DB.saveSupplier(supObj);
            const saved = typeof savedRaw === 'string' ? JSON.parse(savedRaw) : savedRaw;
            const newId = saved?.id || 0;

            const items = this.cardManager.getCardData().map(it => ({
              supplierId: newId,
              productId: +it.productId,
              uomId: +it.uomId,
              pricePerUom: +it.pricePerUom
            }));

            nativeApi.post('saveSupplierItems', { supplierId: newId, items: items });
            setTimeout(() => window.navigate(), 500);
          } catch (err) {
            console.error('Save error:', err);
          }
        }

        _validateForm() {
          const ok = this.form.checkValidity() && this.cardManager.getCardData().length > 0;
          this.saveBtn.disabled = !ok;
          return ok;
        }
      },

      expose() {
        window.SupplierFormApp = new this.SupplierFormApp();
        window.init_supplier_form = window.SupplierFormApp.init.bind(window.SupplierFormApp);
      }
    }
  };

