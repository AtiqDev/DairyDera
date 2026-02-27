window.screenMap.walkin = {
    template: 'walkin.html',
    script: {
        expose(params) {
            function init_walkin(params) {
                document.getElementById('saveWalkin').onclick = async () => {
                    const name = document.getElementById('name').value.trim();
                    if (!name) return alert('name required');

                    const qty  = parseFloat(document.getElementById('qty').value);
                    const rate = parseFloat(document.getElementById('rate').value);

                    const cust = {
                        name: name,
                        address: '',
                        phone: '',
                        rate: rate,
                        quantity: qty,
                        createDate: new Date().toISOString(),
                        classId: 1
                    };

                    const cid = JSON.parse(await nativeApi.call('saveCustomer', cust)).id;

                    const sale = {
                        customerId: cid,
                        saleDate: new Date().toISOString(),
                        quantity: qty,
                        rate: rate,
                        SaleStatusId: 1,
                        createDate: new Date().toISOString()
                    };

                    nativeApi.post('saveSale', sale);
                    navigate();
                };
            }

            window.init_walkin = init_walkin;
        }
    }
};
