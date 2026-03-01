window.screenMap.income_statement = {
    template: 'income_statement.html',
    script: {
        expose(params) {
            function fmt(v) {
                return parseFloat(Math.abs(v || 0)).toLocaleString('en-US', { minimumFractionDigits: 2 });
            }

            function getPresetDates(preset) {
                const now = new Date();
                let start = new Date();
                if (preset === 'week') {
                    const day = now.getDay();
                    start.setDate(now.getDate() - day + (day === 0 ? -6 : 1));
                } else if (preset === 'mtd') {
                    start = new Date(now.getFullYear(), now.getMonth(), 1);
                }
                return { start: start.toISOString().slice(0,10), end: now.toISOString().slice(0,10) };
            }

            function renderStats(rows) {
                const sums = { Income: 0, Expense: 0 };
                rows.forEach(r => {
                    const val = parseFloat(r.badgeValues) || 0;
                    if (r.badgeName === 'Income') sums.Income += val;
                    else sums.Expense += val;
                });

                const netProfit = sums.Income + sums.Expense;

                document.getElementById('stat-strip').innerHTML = `
                    <div class="stat-chip">
                        <span class="stat-chip__label">Gross Revenue</span>
                        <span class="stat-chip__value" style="color:var(--income)">${fmt(sums.Income)}</span>
                    </div>
                    <div class="stat-chip">
                        <span class="stat-chip__label">Total Expenses</span>
                        <span class="stat-chip__value" style="color:var(--expense)">${fmt(sums.Expense)}</span>
                    </div>
                    <div class="stat-chip" style="border-color:var(--brand)">
                        <span class="stat-chip__label">Net Profit</span>
                        <span class="stat-chip__value" style="color:var(--brand)">${fmt(netProfit)}</span>
                    </div>`;
                document.getElementById('badge-count').textContent = `${rows.length} accounts`;
            }

            async function loadReport() {
                const from = document.getElementById('tx-from').value;
                const to = document.getElementById('tx-to').value;
                const btn = document.getElementById('loadBtn');
                btn.classList.add('spinning');

                try {
                    const raw = await nativeApi.call('getIncomeStatement', { fromDate: from, toDate: to });
                    const rows = JSON.parse(raw || "[]");
                    renderStats(rows);

                    if (!rows.length) {
                        document.getElementById('reportContainer').innerHTML = '<div style="padding:40px;text-align:center;color:var(--text-3)">No data for this period.</div>';
                        return;
                    }

                    document.getElementById('reportContainer').innerHTML = `
                        <table>
                            <thead><tr><th>Account</th><th>Type</th><th style="text-align:right">Net</th></tr></thead>
                            <tbody>
                                ${rows.map(r => {
                                    const val = parseFloat(r.net || 0);
                                    const colorClass = val >= 0 ? 'val-positive' : 'val-negative';
                                    return `
                                        <tr>
                                            <td>
                                                <div style="font-weight:600;color:var(--text-1)">${r.name}</div>
                                                <div style="font-size:10px;color:var(--text-3);font-family:var(--mono)">${r.code}</div>
                                            </td>
                                            <td><span class="acc-type-badge">${r.accountType}</span></td>
                                            <td style="text-align:right;font-weight:700;font-family:var(--mono)" class="${colorClass}">
                                                ${val < 0 ? '('+fmt(val)+')' : fmt(val)}
                                            </td>
                                        </tr>
                                    `;
                                }).join('')}
                            </tbody>
                        </table>`;
                } catch (e) { console.error(e); } finally { btn.classList.remove('spinning'); }
            }

            function init_income_statement(params = {}) {
                const mtd = getPresetDates('mtd');
                document.getElementById('tx-from').value = params.start || mtd.start;
                document.getElementById('tx-to').value = params.end || mtd.end;

                const updateSummary = () => {
                    document.getElementById('summary-from').textContent = document.getElementById('tx-from').value;
                    document.getElementById('summary-to').textContent = document.getElementById('tx-to').value;
                };
                updateSummary();

                document.getElementById('toolbar-summary').onclick = () => document.getElementById('toolbar-panel').classList.toggle('open');

                document.querySelectorAll('.preset-pill').forEach(pill => {
                    pill.onclick = (e) => {
                        e.stopPropagation();
                        document.querySelectorAll('.preset-pill').forEach(p => p.classList.remove('active'));
                        pill.classList.add('active');
                        const range = getPresetDates(pill.dataset.preset);
                        document.getElementById('tx-from').value = range.start;
                        document.getElementById('tx-to').value = range.end;
                        updateSummary();
                        loadReport();
                    };
                });

                document.getElementById('tx-from').onchange = () => { updateSummary(); loadReport(); };
                document.getElementById('tx-to').onchange = () => { updateSummary(); loadReport(); };
                document.getElementById('backBtn').onclick = () => history.back();
                document.getElementById('loadBtn').onclick = (e) => { e.stopPropagation(); loadReport(); };

                loadReport();
            }

            window.init_income_statement = init_income_statement;
        }
    }
};
