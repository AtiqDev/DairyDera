window.screenMap.pl_report = {
    template: 'pl_report.html',
    script: {
        expose(params) {
            async function loadProfitLoss() {
                const from = document.getElementById('pl-from').value;
                const to   = document.getElementById('pl-to').value;
                const raw_data = await nativeApi.call('getProfitAndLoss', {from, to});
                const data = JSON.parse(raw_data);
                const tbody = document.getElementById('pl-table').querySelector('tbody');
                const stats = document.getElementById('plStats');

                if (!data.length) {
                    stats.textContent = 'No data found.';
                    tbody.innerHTML   = '';
                    return;
                }

                let revenueTotal = 0, expenseTotal = 0;
                data.forEach(r => {
                    if (r.Type === 'Revenue') revenueTotal += r.Net;
                    if (r.Type === 'Expense') expenseTotal += r.Net;
                });

                const profit = revenueTotal + expenseTotal;
                stats.textContent = `Revenue: ${revenueTotal.toFixed(2)} | Expenses: ${(-expenseTotal).toFixed(2)} | Net Profit: ${profit.toFixed(2)}`;

                tbody.innerHTML = data.map(r => `
                    <tr>
                        <td>${r.code}</td>
                        <td>${r.name}</td>
                        <td>${r.Type}</td>
                        <td class="text-end">${r.Net.toFixed(2)}</td>
                    </tr>
                `).join('');
            }

            function init_pl_report(params = {}) {
                const today = new Date().toISOString().slice(0, 10);
                document.getElementById('pl-from').value = today;
                document.getElementById('pl-to').value   = today;
                loadProfitLoss();
            }

            window.loadProfitLoss = loadProfitLoss;
            window.init_pl_report = init_pl_report;
        }
    }
};
