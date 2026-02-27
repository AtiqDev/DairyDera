window.screenMap.cash_flow = {
    template: 'cash_flow.html',
    script: {
        expose(params) {
            const PALETTES = [
                { bg: 'rgba(16,185,129,.09)',  color: '#10b981' },
                { bg: 'rgba(59,130,246,.09)',   color: '#3b82f6' },
                { bg: 'rgba(245,158,11,.09)',   color: '#f59e0b' }
            ];
            const colorMap = {};
            let colorIdx = 0;
            function palette(key) {
                if (!colorMap[key]) colorMap[key] = PALETTES[colorIdx++ % PALETTES.length];
                return colorMap[key];
            }

            function fmt(v) {
                const n = parseFloat(v || 0);
                return n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
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
                let totalIn = 0, totalOut = 0;
                const dateMap = new Map();

                rows.forEach(r => {
                    const val = parseFloat(r.badgeValues) || 0;
                    if (r.flowType === 'Inflow') totalIn += val;
                    else totalOut += val;
                    dateMap.set(r.badgeName, (dateMap.get(r.badgeName) || 0) + val);
                });

                const topDates = [...dateMap.entries()]
                    .sort((a,b) => b[1] - a[1])
                    .slice(0, 3);

                const dateChips = topDates.map(([date, val]) => {
                    const p = palette(date);
                    return `
                        <div class="stat-chip" style="border-color:${p.color}33">
                            <span class="stat-chip__label" style="color:${p.color}">${date}</span>
                            <span class="stat-chip__value" style="font-size:11px">${fmt(val)}</span>
                        </div>`;
                }).join('');

                document.getElementById('stat-strip').innerHTML = `
                    <div class="stat-chip">
                        <span class="stat-chip__label">Net Flow</span>
                        <span class="stat-chip__value" style="color:var(--text-1)">${fmt(totalIn - totalOut)}</span>
                    </div>
                    <div class="stat-chip">
                        <span class="stat-chip__label">Inflows</span>
                        <span class="stat-chip__value inflow">+${fmt(totalIn)}</span>
                    </div>
                    <div class="stat-chip">
                        <span class="stat-chip__label">Outflows</span>
                        <span class="stat-chip__value outflow">-${fmt(totalOut)}</span>
                    </div>
                    ${dateChips}`;

                document.getElementById('badge-count').textContent = `${rows.length} records`;
            }

            async function loadCashFlow() {
                const from = document.getElementById('tx-from').value;
                const to = document.getElementById('tx-to').value;
                const btn = document.getElementById('loadBtn');
                btn.classList.add('spinning');

                try {
                    const raw = await nativeApi.call('getCashFlow', { fromDate: from, toDate: to });
                    const rows = JSON.parse(raw || "[]");
                    renderStats(rows);

                    if (!rows.length) {
                        document.getElementById('reportContainer').innerHTML = '<div style="padding:50px;text-align:center;color:var(--text-3)">No transactions found.</div>';
                        return;
                    }

                    document.getElementById('reportContainer').innerHTML = `
                        <table>
                            <thead><tr><th>Date</th><th>Type</th><th style="text-align:right">Amount</th></tr></thead>
                            <tbody>
                                ${rows.map((r, i) => `
                                    <tr style="animation-delay:${i * 15}ms">
                                        <td style="font-family:var(--mono);font-size:12px;font-weight:600">${r.date}</td>
                                        <td>
                                            <span class="type-badge type-${r.flowType.toLowerCase()}">
                                                <span class="dir-dot" style="background:currentColor"></span>${r.flowType}
                                            </span>
                                        </td>
                                        <td style="text-align:right;font-weight:700;font-family:var(--mono)">${fmt(r.amount)}</td>
                                    </tr>
                                `).join('')}
                            </tbody>
                        </table>`;
                } catch (e) { console.error(e); } finally { btn.classList.remove('spinning'); }
            }

            function init_cash_flow(params = {}) {
                const mtd = getPresetDates('mtd');
                document.getElementById('tx-from').value = params.start || mtd.start;
                document.getElementById('tx-to').value = params.end || mtd.end;

                const updateSummary = () => {
                    document.getElementById('summary-from').textContent = document.getElementById('tx-from').value;
                    document.getElementById('summary-to').textContent = document.getElementById('tx-to').value;
                };
                updateSummary();

                document.getElementById('toolbar-summary').onclick = () => {
                    const isOpen = document.getElementById('toolbar-panel').classList.toggle('open');
                    document.getElementById('toolbar-chevron').classList.toggle('open', isOpen);
                };

                document.querySelectorAll('.preset-pill').forEach(pill => {
                    pill.onclick = (e) => {
                        e.stopPropagation();
                        document.querySelectorAll('.preset-pill').forEach(p => p.classList.remove('active'));
                        pill.classList.add('active');
                        const range = getPresetDates(pill.dataset.preset);
                        document.getElementById('tx-from').value = range.start;
                        document.getElementById('tx-to').value = range.end;
                        updateSummary();
                        loadCashFlow();
                    };
                });

                document.getElementById('tx-from').onchange = () => { updateSummary(); document.querySelectorAll('.preset-pill').forEach(p => p.classList.remove('active')); };
                document.getElementById('tx-to').onchange = () => { updateSummary(); document.querySelectorAll('.preset-pill').forEach(p => p.classList.remove('active')); };

                document.getElementById('backBtn').onclick = () => history.back();
                document.getElementById('loadBtn').onclick = (e) => { e.stopPropagation(); loadCashFlow(); };

                loadCashFlow();
            }

            window.init_cash_flow = init_cash_flow;
            window.loadCashFlow = loadCashFlow;
        }
    }
};
