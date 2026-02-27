window.screenMap.report_sale_edit = {
    template: 'report_sale_edit.html',
    script: {
        expose(params) {
            async function init_report_sale_edit(params) {
                const customers_raw = await nativeApi.call('getCustomers');
                const customers = JSON.parse(customers_raw);
                const sales_raw = await nativeApi.call('getSales');
                const sales     = JSON.parse(sales_raw);
                const sale      = sales.find(s => s.id == params.id) || {};
                const cust      = customers.find(c => c.id === sale.customerId) || {};
                const statuses_raw = await nativeApi.call('getSaleStatus');
                const statuses  = JSON.parse(statuses_raw);

                document.getElementById('rseCust').textContent = cust.name || '—';
                document.getElementById('rate').value  = sale.rate     || cust.rate     || 0;
                document.getElementById('qty').value   = sale.quantity || cust.quantity || 0;
                document.getElementById('notes').value = sale.feedbackNotes || '';

                statuses.forEach(s => {
                    const o = document.createElement('option');
                    o.value = s.id;
                    o.textContent = s.name;
                    if (s.id === sale.SaleStatusId) o.selected = true;
                    document.getElementById('status').append(o);
                });

                document.getElementById('saveReportSale').onclick = async () => {
                    const updated = {
                        id:             sale.id,
                        customerId:     sale.customerId,
                        saleDate:       sale.saleDate || params.date,
                        quantity:       parseFloat(document.getElementById('qty').value),
                        rate:           parseInt(document.getElementById('rate').value, 10),
                        SaleStatusId:   parseInt(document.getElementById('status').value, 10),
                        feedbackNotes: document.getElementById('notes').value.trim(),
                        createDate:     sale.createDate || new Date().toISOString(),
                        updateDate:     new Date().toISOString()
                    };
                    nativeApi.post('saveSale', updated);
                    navigate();
                };
            }

            window.init_report_sale_edit = init_report_sale_edit;
        }
    }
};
