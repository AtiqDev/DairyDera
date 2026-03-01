window.screenMap.report_purchase_edit = {
    template: 'report_purchase_edit.html',
    script: {
        expose(params) {
            const ns = {};

            ns.updateAmount = function() {
                const r = parseFloat(ns.rateEl.value) || 0;
                const q = parseFloat(ns.qtyEl.value) || 0;
                ns.amtEl.value = (r * q).toFixed(2);
            };

            ns.updateRate = function() {
                const q = parseFloat(ns.qtyEl.value) || 0;
                const a = parseFloat(ns.amtEl.value) || 0;
                ns.rateEl.value = q > 0 ? (a / q).toFixed(2) : 0;
            };

            async function init_report_purchase_edit(params) {
                const suppliers_raw = await nativeApi.call('getSuppliers');
                const suppliers = JSON.parse(suppliers_raw);
                const purchases_raw = await nativeApi.call('getPurchases');
                const purchases = JSON.parse(purchases_raw);
                const purchase = purchases.find(p => p.id == params.id) || {};
                const sup = suppliers.find(s => s.id === purchase.supplierId) || {};
                const statuses_raw = await nativeApi.call('getPurchaseStatus');
                const statuses = JSON.parse(statuses_raw);

                document.getElementById('rpeSup').textContent = sup.name || '—';
                document.getElementById('item').value = purchase.Item || '';
                document.getElementById('rate').value = purchase.rate ?? sup.rate ?? 0;
                document.getElementById('qty').value = purchase.quantity ?? sup.quantity ?? 0;
                document.getElementById('amount').value = purchase.amount ?? (purchase.rate * purchase.quantity) ?? 0;
                document.getElementById('notes').value = purchase.notes || '';

                const sel = document.getElementById('status');
                sel.innerHTML = '';
                statuses.forEach(s => {
                    const o = document.createElement('option');
                    o.value = s.id;
                    o.textContent = s.name;
                    if (s.id === purchase.statusId) o.selected = true;
                    sel.appendChild(o);
                });

                ns.rateEl = document.getElementById('rate');
                ns.qtyEl = document.getElementById('qty');
                ns.amtEl = document.getElementById('amount');

                ns.rateEl.addEventListener('input', ns.updateAmount);
                ns.qtyEl.addEventListener('input', ns.updateAmount);
                ns.amtEl.addEventListener('input', ns.updateRate);
                ns.updateAmount();

                document.getElementById('saveReportPurchase').onclick = async () => {
                    const updated = {
                        id: purchase.id,
                        supplierId: purchase.supplierId,
                        purchaseDate: purchase.purchaseDate || params.date,
                        Item: document.getElementById('item').value.trim(),
                        quantity: parseFloat(ns.qtyEl.value),
                        rate: parseFloat(ns.rateEl.value),
                        amount: parseFloat(ns.amtEl.value),
                        statusId: parseInt(sel.value, 10),
                        notes: document.getElementById('notes').value.trim(),
                        createDate: purchase.createDate || new Date().toISOString(),
                        updateDate: new Date().toISOString()
                    };

                    nativeApi.post('savePurchase', updated);
                    navigate();
                };
            }

            window.init_report_purchase_edit = init_report_purchase_edit;
        }
    }
};
