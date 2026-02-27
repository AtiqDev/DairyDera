window.screenMap.purchase_asset = {
    template: 'purchase_asset.html',
    script: {
        expose(params) {
            'use strict';

            let products = [], units = [], suppliers = [];
            const staticInner = document.getElementById('staticItemsGridInner');
            const addItemPopup = document.getElementById('addItemPopup');
            const popupTitle = document.getElementById('popupTitle');
            const popupProduct = document.getElementById('popupProductSelect');
            const popupQty = document.getElementById('popupQty');
            const popupPrice = document.getElementById('popupPrice');
            const popupUom = document.getElementById('popupUomSelect');
            const popupCancel = document.getElementById('popupCancel');
            const popupSave = document.getElementById('popupSave');
            let editCard = null;

            async function safeBridge(fn, def = []) {
                try {
                    const raw = await nativeApi.call(fn);
                    return raw ? JSON.parse(raw) : def;
                } catch (e) {
                    console.error(`nativeApi.${fn} error:`, e);
                    return def;
                }
            }

            function populateProductOptions(selectedId) {
                let html = '<option value="">Select Product</option>';
                for (let p of products) {
                    const sel = selectedId === p.id ? ' selected' : '';
                    html += `<option value="${p.id}"${sel}>${escapeHtml(p.name)}</option>`;
                }
                popupProduct.innerHTML = html;
            }

            function populateUomOptions(selectedId) {
                let html = '<option value="">Select Unit</option>';
                for (let u of units) {
                    const sel = selectedId === u.id ? ' selected' : '';
                    html += `<option value="${u.id}"${sel}>${escapeHtml(u.name)}</option>`;
                }
                popupUom.innerHTML = html;
            }

            function escapeHtml(s) {
                if (!s && s !== 0) return '';
                return String(s).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
            }

            function computeTotal() {
                const cards = Array.from(staticInner.children);
                const total = cards.reduce((sum, c) => sum + (+c.dataset.lineTotal || 0), 0);
                document.getElementById('grand-total').textContent = total.toFixed(2);
            }

            function validateForm() {
                const form = document.getElementById('purchase-form');
                const isValid = form.checkValidity() && staticInner.children.length > 0;
                document.getElementById('saveBtn').disabled = !isValid;
                return isValid;
            }

            function closePopup() {
                addItemPopup.style.display = 'none';
                editCard = null;
            }

            function attachCardEvents(card) {
                const removeBtn = card.querySelector('.remove-line');
                removeBtn.onclick = (e) => {
                    e.stopPropagation();
                    card.remove();
                    computeTotal();
                    validateForm();
                };

                card.onclick = (e) => {
                    if (e.target.closest('.remove-line')) return;
                    document.querySelectorAll('#staticItemsGridInner > div').forEach(c => {
                        c.classList.remove('selected');
                        c.style.boxShadow = '';
                    });
                    card.classList.add('selected');
                    editCard = card;

                    const productId = +card.dataset.productId;
                    const uomId = +card.dataset.uomId;
                    const qty = +card.dataset.qty;
                    const price = +card.dataset.price;

                    populateProductOptions(productId);
                    populateUomOptions(uomId);
                    popupQty.value = qty;
                    popupPrice.value = price;
                    popupTitle.textContent = 'Edit Milk Line';
                    addItemPopup.style.display = 'flex';
                };
            }

            // Open Add Popup
            document.getElementById('btnAddItem').onclick = () => {
                if (!document.getElementById('supplier-select').value) {
                    alert('Please select a supplier first.');
                    return;
                }
                editCard = null;
                popupTitle.textContent = 'Add Milk Line';
                populateProductOptions();
                populateUomOptions();
                popupQty.value = '1';
                popupPrice.value = '0';
                addItemPopup.style.display = 'flex';
            };

            popupCancel.onclick = closePopup;

            popupSave.onclick = () => {
                const productId = +popupProduct.value;
                const qty = parseFloat(popupQty.value);
                const price = parseFloat(popupPrice.value);
                const uomId = +popupUom.value;

                if (!productId) return alert('Select a product');
                if (qty <= 0) return alert('quantity must be > 0');

                const lineTotal = Math.round(qty * price * 100) / 100;

                if (editCard) {
                    editCard.dataset.productId = productId;
                    editCard.dataset.qty = qty;
                    editCard.dataset.price = price;
                    editCard.dataset.uomId = uomId;
                    editCard.dataset.lineTotal = lineTotal;

                    const product = products.find(p => p.id === productId);
                    const unit = units.find(u => u.id === uomId);

                    editCard.querySelector('.card-title').textContent = product?.name || 'Unknown';
                    editCard.querySelector('.card-meta').textContent = `Qty: ${qty} • price: ${price} • Total: ${lineTotal.toFixed(2)}`;
                    editCard.querySelector('.card-unit').textContent = `Unit: ${unit?.name || ''}`;
                    editCard.classList.remove('selected');
                    editCard = null;
                } else {
                    const product = products.find(p => p.id === productId);
                    const unit = units.find(u => u.id === uomId);

                    const card = document.createElement('div');
                    card.className = 'border rounded p-2 bg-white static-card';
                    card.innerHTML = `
                        <button type="button" class="btn btn-sm btn-outline-danger remove-line" aria-label="Remove">
                            <i class="fas fa-trash"></i>
                        </button>
                        <div class="fw-semibold card-title mb-1">${escapeHtml(product?.name || 'Unknown')}</div>
                        <div class="small card-meta">Qty: ${qty} • price: ${price} • Total: ${lineTotal.toFixed(2)}</div>
                        <div class="small text-muted card-unit">Unit: ${escapeHtml(unit?.name || '')}</div>
                    `;
                    card.dataset.productId = productId;
                    card.dataset.qty = qty;
                    card.dataset.price = price;
                    card.dataset.uomId = uomId;
                    card.dataset.lineTotal = lineTotal;

                    staticInner.appendChild(card);
                    attachCardEvents(card);
                }

                closePopup();
                computeTotal();
                validateForm();
            };

            document.getElementById('purchase-form').onsubmit = async (e) => {
                e.preventDefault();
                const form = e.target;
                form.classList.add('was-validated');
                if (!validateForm()) return;

                if (!confirm('Save purchase?')) return;

                const supplierId = +document.getElementById('supplier-select').value;
                const date = document.getElementById('purchase-date').value;

                const items = Array.from(staticInner.children).map(card => ({
                    product_id: +card.dataset.productId,
                    quantity: +card.dataset.qty,
                    uomId: +card.dataset.uomId,
                    price_per_uom: +card.dataset.price
                }));

                nativeApi.post('saveAssetPurchase', {
                    supplier_id: supplierId,
                    purchase_date: date,
                    items: items
                });
                navigate();
            };

            document.querySelectorAll('#staticItemsGridInner > div').forEach(attachCardEvents);

            async function init_purchase_asset(params = {}) {
                const today = new Date().toISOString().slice(0, 10);
                document.getElementById('purchase-date').value = today;

                suppliers = await safeBridge('getSuppliers');
                products = await safeBridge('getAllProducts');
                units = await safeBridge('getAllUnits');

                const supSel = document.getElementById('supplier-select');
                supSel.innerHTML = '<option value="">Select supplier</option>' +
                    suppliers.map(s => `<option value="${s.id}">${s.name}</option>`).join('');

                populateProductOptions();
                populateUomOptions();
                validateForm();
            }

            window.init_purchase_asset = init_purchase_asset;
        }
    }
};
