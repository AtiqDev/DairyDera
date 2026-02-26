window.screenMap.purchase = {
    template: 'purchase.html',
    script: {
      // --- 1. DataBridge ---
      DataBridge: {
        async safeCall(fnName, defaultVal = [], payload = {}) {
            try {
                const raw = await nativeApi.call(fnName, payload);
                const result = raw ? JSON.parse(raw) : defaultVal;
                console.info(`[Bridge] ${fnName} success (Count: ${result.length || 0})`);
                return result;
            } catch (err) {
                console.error(`[Bridge] ❌ ${fnName} error:`, err);
                return defaultVal;
            }
        },
        async getSuppliers() { return this.safeCall('getSuppliers'); },
        async getUOMList() { return this.safeCall('getAllUnits'); },
        async getPurchaseStatus() { return this.safeCall('getPurchaseStatus'); },
        async getSupplierItems(supplierId) { return this.safeCall('getSupplierItems', [], { supplierId }); },
        async getPurchase(id) { return this.safeCall('getPurchase', null, { purchaseId: id }); },
        async getPurchaseItems(id) { return this.safeCall('getPurchaseItems', [], { purchaseId: id }); },
        async savePurchase(payload) { return this.safeCall('savePurchase', null, payload); },
        async deletePurchase(id) { return nativeApi.post('deletePurchase', { id }); }
      },

      // --- 2. CardManager ---
      CardManager: class {
        constructor(containerId, editCallback) {
          this.container = document.getElementById(containerId);
          this.editCallback = editCallback;
          this.container.addEventListener('click', this._handleContainerClick.bind(this));
          this.noItemsMessage = document.getElementById('no-items-message');
          console.log('[CardManager] Initialized.');
        }
        _updateNoItemsMessage() {
          const itemCardsCount = this.container.querySelectorAll('.item-card').length;
          this.noItemsMessage.style.display = itemCardsCount === 0 ? 'block' : 'none';
        }
        clearSelection() {
          Array.from(this.container.children).forEach(c => c.classList.remove('selected'));
        }
        _reindexCards() {
          Array.from(this.container.children).forEach((card, i) => card.dataset.rowIndex = i);
          this._updateNoItemsMessage();
          console.log('[CardManager] Re-indexed cards.');
        }
        _handleContainerClick(ev) {
          const card = ev.target.closest('.item-card');
          if (!card || !this.container.contains(card)) return;
          if (ev.target.closest('.remove-line')) {
            ev.stopPropagation();
            if (confirm('Are you sure you want to remove this item?')) {
              card.remove();
              this._reindexCards();
              window.PurchaseFormApp.recomputeAmounts();
              window.PurchaseFormApp.validateMainForm();
            }
          } else {
            this.clearSelection();
            card.classList.add('selected');
            this.editCallback(card);
          }
        }
        addOrUpdateCard(item, cardToUpdate = null) {
          const lineAmount = Math.round((item.quantity * item.pricePerUom) * 100) / 100;
          const title = (item.itemName || 'Unknown').split('[')[0].trim();
          const qtyText = `Qty: ${item.quantity}`;
          const priceText = `price: ${parseFloat(item.pricePerUom || 0).toFixed(2)}`;
          const meta = `${qtyText} • ${priceText}`;
          const amountFormatted = lineAmount.toLocaleString('en-US', { minimumFractionDigits: 2 });
          const notesText = item.notes ? `notes: ${item.notes}` : '';

          if (cardToUpdate) {
            cardToUpdate.dataset.supplierItemId = item.supplierItemId;
            cardToUpdate.dataset.productId = item.productId;
            cardToUpdate.dataset.uomId = item.uomId;
            cardToUpdate.dataset.qty = item.quantity;
            cardToUpdate.dataset.price = item.pricePerUom;
            cardToUpdate.dataset.lineAmount = lineAmount;
            cardToUpdate.querySelector('.item-name').textContent = title;
            cardToUpdate.querySelector('.item-meta').textContent = meta;
            cardToUpdate.querySelector('.item-amount').textContent = amountFormatted;
            cardToUpdate.querySelector('.item-notes').textContent = notesText;
            cardToUpdate.classList.remove('selected');
          } else {
            const card = document.createElement('div');
            card.className = 'item-card';
            card.innerHTML = `
              <div class="item-details">
                <div class="item-name">${title}</div>
                <div class="item-meta">${meta}</div>
                <div class="small text-muted item-notes">${notesText}</div>
              </div>
              <div class="item-amount">${amountFormatted}</div>
              <div class="item-actions">
                <button type="button" class="btn btn-sm btn-outline-danger remove-line" aria-label="Remove item">
                  <i class="fas fa-trash"></i>
                </button>
              </div>
            `;
            card.dataset.lineId = item.lineId || 0;
            card.dataset.supplierItemId = item.supplierItemId;
            card.dataset.productId = item.productId;
            card.dataset.uomId = item.uomId;
            card.dataset.qty = item.quantity;
            card.dataset.price = item.pricePerUom;
            card.dataset.lineAmount = lineAmount;
            this.container.appendChild(card);
          }
          this._reindexCards();
          window.PurchaseFormApp.recomputeAmounts();
          window.PurchaseFormApp.validateMainForm();
        }
        getCardData() {
          return Array.from(this.container.querySelectorAll('.item-card')).map(c => ({
            lineId: +c.dataset.lineId || 0,
            productId: +c.dataset.productId || 0,
            supplierItemId: +c.dataset.supplierItemId || 0,
            uomId: +c.dataset.uomId || 0,
            quantity: +c.dataset.qty || 0,
            pricePerUom: +c.dataset.price || 0,
            lineAmount: +c.dataset.lineAmount || 0,
          }));
        }
        clear() {
          this.container.innerHTML = `<p class="text-center text-muted m-0 p-3" id="no-items-message">Use "Add Item" to start.</p>`;
          this.noItemsMessage = document.getElementById('no-items-message');
          this._reindexCards();
          window.PurchaseFormApp.recomputeAmounts();
          window.PurchaseFormApp.validateMainForm();
        }
      },

      // --- 3. PurchaseFormApp ---
      PurchaseFormApp: class {
        constructor() {
          this.supplierItems = [];
          this.uoms = [];
          this.editCardRef = null;
          this.cardManager = null;
          console.log('[App] PurchaseFormApp properties initialized.');
        }
        initDomAndEvents() {
          this.form = document.getElementById('purchase-form');
          if (!this.form) return false;
          this.popup = document.getElementById('addItemPopup');
          this.supSel = document.getElementById('supplier-select');
          this.addBtn = document.getElementById('addItemRow');
          this.saveBtn = document.getElementById('savePurchaseForm');
          this.deleteBtn = document.getElementById('deletePurchaseBtn');
          this.totalAmountEl = document.getElementById('totalAmount');
          this.dateIn = document.getElementById('purchase-date');
          this.statusSel = document.getElementById('status-select');
          this.notesIn = document.getElementById('notes');

          this.popupForm = document.getElementById('popupForm');
          this.popupItemSelect = document.getElementById('popupItemSelect');
          this.popupUomSelect = document.getElementById('popupUomSelect');
          this.popupQty = document.getElementById('popupQty');
          this.popupPrice = document.getElementById('popupPrice');
          this.popupNotes = document.getElementById('popupNotes');
          this.popupSave = document.getElementById('popupSaveItem');
          this.popupTitle = document.getElementById('addItemPopupTitle');

          this.cardManager = new window.screenMap.purchase.script.CardManager('items-list-container', this.openEditPopup.bind(this));
          this._setupEventListeners();
          return true;
        }
        _setupEventListeners() {
          this.supSel.addEventListener('change', this.handleSupplierChange.bind(this));
          this.addBtn.addEventListener('click', () => this.openAddPopup());
          this.form.addEventListener('submit', this.handleFormSubmit.bind(this));
          this.form.addEventListener('input', this.validateMainForm.bind(this));
          this.deleteBtn.addEventListener('click', this.handleDelete.bind(this));
          this.form.classList.add('was-validated');
          this.popupForm.addEventListener('submit', this.handlePopupSave.bind(this));
          document.getElementById('popupCancelItem').addEventListener('click', this.closePopup.bind(this));
          this.popupItemSelect.addEventListener('change', this.handlePopupItemChange.bind(this));
          this.popupForm.querySelectorAll('input, select').forEach(el =>
            el.addEventListener('input', this._debounce(this.validatePopupForm.bind(this), 300))
          );
          this.popupForm.classList.add('was-validated');
          this.popup.addEventListener('click', (e) => { if (e.target.id === 'addItemPopup') this.closePopup(); });
        }
        _debounce(fn, delay = 200) {
          let timer;
          return (...args) => { clearTimeout(timer); timer = setTimeout(() => fn.apply(this, args), delay); };
        }
        async init(params = {}) {
          if (!this.initDomAndEvents()) return;
          const DB = window.screenMap.purchase.script.DataBridge;
          const suppliers = await DB.getSuppliers();
          this.uoms = await DB.getUOMList();
          const statuses = await DB.getPurchaseStatus();

          if (!suppliers.length) {
            this.supSel.disabled = this.addBtn.disabled = this.saveBtn.disabled = true;
            this.supSel.innerHTML = '<option value="">No suppliers available</option>';
            return;
          }
          this.supSel.innerHTML = '<option value="">Select Supplier</option>' +
            suppliers.map(s => `<option value="${s.id}">${s.name}</option>`).join('');
          this.statusSel.innerHTML = statuses.map(st => `<option value="${st.id}">${st.name}</option>`).join('');
          this.supSel.disabled = false;

          const purchaseId = +params.id || +params.purchaseId || 0;

          if (purchaseId > 0) {
            document.getElementById('purchaseFormTitle').textContent = `Edit Purchase #${purchaseId}`;
            this.form.dataset.purchaseId = purchaseId;
            this.deleteBtn.style.display = 'block';
            await this.loadPurchaseData(purchaseId);
          } else {
            document.getElementById('purchaseFormTitle').textContent = 'New Purchase Order';
            this.dateIn.value = new Date().toISOString().slice(0, 10);
            if (params.supplierId) {
              this.supSel.value = params.supplierId;
              await this.handleSupplierChange();
            }
          }
          this.validateMainForm();
        }
        async loadPurchaseData(purchaseId) {
          const DB = window.screenMap.purchase.script.DataBridge;
          const hdr = await DB.getPurchase(purchaseId);
          if (!hdr) return;
          this.supSel.value = hdr.supplierId;
          this.dateIn.value = (hdr.purchaseDate || new Date().toISOString()).slice(0, 10);
          this.statusSel.value = hdr.statusId;
          this.notesIn.value = hdr.notes || '';
          this.supplierItems = await DB.getSupplierItems(hdr.supplierId);
          const lines = await DB.getPurchaseItems(purchaseId);
          lines.forEach(item => this.cardManager.addOrUpdateCard(item));
          this.recomputeAmounts();
        }
        async handleSupplierChange() {
          const DB = window.screenMap.purchase.script.DataBridge;
          const supplierId = this.supSel.value;
          this.cardManager.clear();
          this.supplierItems = await DB.getSupplierItems(supplierId);
          this.validateMainForm();
        }
        async handleFormSubmit(e) {
          e.preventDefault();
          if (!this.validateMainForm()) return;
          const DB = window.screenMap.purchase.script.DataBridge;
          const purchaseId = +this.form.dataset.purchaseId || 0;
          const payload = {
            id: purchaseId,
            supplierId: +this.supSel.value,
            purchaseDate: this.dateIn.value,
            statusId: +this.statusSel.value,
            notes: this.notesIn.value.trim(),
            items: this.cardManager.getCardData()
          };
          this.saveBtn.disabled = true;
          const statusRaw = await DB.savePurchase(payload);
          const statusObject = typeof statusRaw === 'string' ? JSON.parse(statusRaw) : statusRaw;
          if (statusObject && (statusObject.id || statusObject.isGood)) {
            setTimeout(() => { window.navigate(); }, 500);
          } else {
            this.saveBtn.disabled = false;
            alert("Error saving purchase.");
          }
        }
        async handleDelete() {
          const DB = window.screenMap.purchase.script.DataBridge;
          const purchaseId = +this.form.dataset.purchaseId;
          if (purchaseId > 0 && confirm(`Are you sure you want to DELETE Purchase Order #${purchaseId}?`)) {
            this.deleteBtn.disabled = true;
            await DB.deletePurchase(purchaseId);
            setTimeout(() => { window.navigate(); }, 500);
          }
        }
        handlePopupItemChange() {
          const sel = this.popupItemSelect.selectedOptions[0];
          if (!sel || !sel.value) return;
          this.popupUomSelect.value = sel.dataset.uomId || '';
          this.popupPrice.value = parseFloat(sel.dataset.pricePerUom || 0).toFixed(2);
          this.popupQty.value = 1;
          this.validatePopupForm();
        }
        handlePopupSave(e) {
          e.preventDefault();
          if (!this.validatePopupForm()) return;
          const opt = this.popupItemSelect.selectedOptions[0];
          const newItem = {
            lineId: this.editCardRef ? +this.editCardRef.dataset.lineId : 0,
            supplierItemId: +opt.value,
            productId: +opt.dataset.productId || 0,
            uomId: +this.popupUomSelect.value || +opt.dataset.uomId || 0,
            quantity: parseFloat(this.popupQty.value) || 0,
            pricePerUom: parseFloat(this.popupPrice.value) || 0,
            notes: this.popupNotes.value.trim(),
            itemName: opt.textContent.split('[')[0].trim()
          };
          this.cardManager.addOrUpdateCard(newItem, this.editCardRef);
          this.closePopup();
        }
        validateMainForm() {
          const hasSupplier = !!this.supSel.value;
          const hasItems = this.cardManager.getCardData().length > 0;
          const ok = hasSupplier && hasItems && this.form.checkValidity();
          this.addBtn.disabled = !hasSupplier;
          this.saveBtn.disabled = !ok;
          return ok;
        }
        validatePopupForm() {
          const ok = !!this.popupItemSelect.value && parseFloat(this.popupQty.value) > 0 && parseFloat(this.popupPrice.value) > 0;
          this.popupSave.disabled = !ok;
          return ok;
        }
        recomputeAmounts() {
          const total = this.cardManager.getCardData().reduce((sum, item) => sum + (+item.lineAmount || 0), 0);
          this.totalAmountEl.textContent = total.toLocaleString('en-US', { minimumFractionDigits: 2 });
        }
        async populatePopupOptions(selectedSupplierItemId) {
          let itemHtml = '<option value="">Select Product</option>';
          for (let it of this.supplierItems) {
            const isSelected = selectedSupplierItemId && it.id === selectedSupplierItemId ? ' selected' : '';
            itemHtml += `<option value="${it.id}" data-product-id="${it.productId}" data-uom-id="${it.uomId}" data-price-per-uom="${it.pricePerUom}"${isSelected}>${it.itemName} [${it.uomName || 'N/A'}]</option>`;
          }
          this.popupItemSelect.innerHTML = itemHtml;
          this.popupUomSelect.innerHTML = '<option value="">Default UOM</option>' + this.uoms.map(u => `<option value="${u.id}">${u.name}</option>`).join('');
        }
        openAddPopup() {
          if (!this.supSel.value) return alert('Please select a supplier first.');
          this.editCardRef = null;
          this.popupTitle.textContent = 'Add Purchase Item';
          this.popupQty.value = 1;
          this.popupPrice.value = '';
          this.popupNotes.value = '';
          this.populatePopupOptions();
          this.popup.style.display = 'flex';
          this.validatePopupForm();
        }
        openEditPopup(card) {
          this.editCardRef = card;
          this.popupTitle.textContent = 'Edit Purchase Item';
          const itemId = +card.dataset.supplierItemId;
          this.populatePopupOptions(itemId);
          this.popupItemSelect.value = itemId || '';
          this.popupUomSelect.value = +card.dataset.uomId || '';
          this.popupQty.value = +card.dataset.qty || 1;
          this.popupPrice.value = parseFloat(card.dataset.price || 0).toFixed(2);
          const notesEl = card.querySelector('.item-notes');
          this.popupNotes.value = notesEl ? notesEl.textContent.replace(/^notes:\s*/,'') : '';
          this.popup.style.display = 'flex';
          this.validatePopupForm();
        }
        closePopup() {
          this.popup.style.display = 'none';
          this.editCardRef = null;
          this.cardManager.clearSelection();
        }
      },
      expose() {
        window.PurchaseFormApp = new this.PurchaseFormApp();
        window.init_purchase = window.PurchaseFormApp.init.bind(window.PurchaseFormApp);
      }
    }
  };

