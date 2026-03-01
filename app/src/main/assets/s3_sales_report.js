window.screenMap.sales_report = {
    template: 'sales_report.html',
    script: {
        expose(params) {
            function today()        { return new Date().toISOString().slice(0, 10); }
            function firstOfMonth() {
                const n = new Date();
                return new Date(n.getFullYear(), n.getMonth(), 1).toISOString().slice(0, 10);
            }
            function startOfWeek() {
                const d = new Date(); d.setDate(d.getDate() - d.getDay());
                return d.toISOString().slice(0, 10);
            }
            function fmtDisplayDate(iso) {
                if (!iso) return '—';
                const [y, m, d] = iso.split('-');
                const months = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];
                return `${months[parseInt(m,10)-1]} ${parseInt(d,10)}, ${y}`;
            }

            function fmtAmount(val) {
                const n = parseFloat(val);
                return isNaN(n) ? '—'
                    : n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
            }
            function fmtQty(val) {
                const n = parseFloat(val);
                return isNaN(n) ? '—'
                    : n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
            }

            const PALETTES = [
                { color: '#2563eb' }, { color: '#16a34a' }, { color: '#d97706' },
                { color: '#7c3aed' }, { color: '#0d9488' }, { color: '#dc2626' },
                { color: '#4b0082' },
            ];
            const colorMap = {};
            let colorIdx = 0;
            function palette(key) {
                if (!colorMap[key]) colorMap[key] = PALETTES[colorIdx++ % PALETTES.length];
                return colorMap[key];
            }

            function showLoading() {
                document.getElementById('stat-strip').innerHTML = '';
                document.getElementById('badge-count').textContent = '—';
                document.getElementById('reportContainer').innerHTML =
                    `<div class="state-box"><div class="spinner"></div><p>Loading…</p></div>`;
            }
            function showEmpty() {
                document.getElementById('stat-strip').innerHTML = '';
                document.getElementById('badge-count').textContent = '0 records';
                document.getElementById('reportContainer').innerHTML = `
                    <div class="state-box">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                            <path d="M6 2h12l4 4v14a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z"/>
                            <polyline points="16 2 16 8 8 8"/><line x1="8" y1="13" x2="16" y2="13"/>
                            <line x1="8" y1="17" x2="12" y2="17"/>
                        </svg>
                        <p>No sales found for this period.</p>
                    </div>`;
            }
            function showError(msg) {
                document.getElementById('stat-strip').innerHTML = '';
                document.getElementById('badge-count').textContent = 'Error';
                document.getElementById('reportContainer').innerHTML = `
                    <div class="state-box">
                        <svg viewBox="0 0 24 24" fill="none" stroke="#dc2626" stroke-width="1.5">
                            <circle cx="12" cy="12" r="10"/>
                            <line x1="12" y1="8" x2="12" y2="12"/>
                            <circle cx="12" cy="16" r=".6" fill="#dc2626"/>
                        </svg>
                        <p class="err">${msg}</p>
                    </div>`;
            }

            function renderStats(rows) {
                const count = rows.length;
                document.getElementById('badge-count').textContent =
                    `${count} sale${count !== 1 ? 's' : ''}`;

                if (!rows.length) {
                    document.getElementById('stat-strip').innerHTML = '';
                    return;
                }

                const badgeMap = new Map();
                rows.forEach(r => {
                    const name = r._customerName || '—';
                    const val  = (parseFloat(r.quantity) || 0) * (parseFloat(r.rate) || 0);
                    badgeMap.set(name, (badgeMap.get(name) || 0) + val);
                });

                const grandTotal = [...badgeMap.values()].reduce((s, v) => s + v, 0);

                const chips = [...badgeMap.entries()].map(([name, total]) => {
                    const pal = palette(name);
                    return `
                        <div class="stat-chip" style="border-color:${pal.color}44">
                            <span class="stat-chip__label" style="color:${pal.color}99">${name}</span>
                            <span class="stat-chip__value" style="color:${pal.color};filter:drop-shadow(0 0 4px ${pal.color}66)">
                                ${fmtAmount(total)}
                            </span>
                        </div>`;
                }).join('');

                document.getElementById('stat-strip').innerHTML = `
                    <div class="stat-chip">
                        <span class="stat-chip__label">Total Sales</span>
                        <span class="stat-chip__value green">${fmtAmount(grandTotal)}</span>
                    </div>
                    ${chips}`;
            }

            function renderTable(rows) {
                if (!rows.length) { showEmpty(); return; }
                renderStats(rows);

                const grandTotal = rows.reduce((s, r) =>
                    s + (parseFloat(r.quantity) || 0) * (parseFloat(r.rate) || 0), 0);
                const totalQty = rows.reduce((s, r) =>
                    s + (parseFloat(r.quantity) || 0), 0);

                const bodyHtml = rows.map((r, i) => {
                    const saleAmount = (parseFloat(r.quantity) || 0) * (parseFloat(r.rate) || 0);
                    const delay = Math.min(i * 12, 380);
                    return `
                        <tr style="animation-delay:${delay}ms" data-id="${r.id}">
                            <td class="c-bold">${r._customerName ?? '—'}</td>
                            <td class="qty-cell">${fmtQty(r.quantity)}</td>
                            <td class="c-mono c-right c-nowrap" style="color:var(--text-2);font-size:12px">
                                ${fmtAmount(r.rate)}
                            </td>
                            <td class="amount-cell">${fmtAmount(saleAmount)}</td>
                        </tr>`;
                }).join('');

                document.getElementById('reportContainer').innerHTML = `
                    <div class="table-scroll">
                        <table>
                            <thead>
                                <tr>
                                    <th>Customer</th>
                                    <th class="right">Qty</th>
                                    <th class="right">Rate</th>
                                    <th class="right">Amount</th>
                                </tr>
                            </thead>
                            <tbody>${bodyHtml}</tbody>
                            <tfoot>
                                <tr>
                                    <td style="text-align:right;color:var(--text-2);font-size:11px;letter-spacing:.06em;text-transform:uppercase;">Total</td>
                                    <td class="c-right c-mono" style="color:var(--text-1)">${fmtQty(totalQty)}</td>
                                    <td></td>
                                    <td class="amount-cell">${fmtAmount(grandTotal)}</td>
                                </tr>
                            </tfoot>
                        </table>
                    </div>`;

                document.querySelectorAll('#reportContainer tr[data-id]').forEach(tr => {
                    tr.addEventListener('click', () => {
                        navigate('report_sale_edit', {
                            id: tr.dataset.id,
                            date: document.getElementById('sl-from').value
                        });
                    });
                });
            }

            async function loadSaleReport() {
                const fromDate = document.getElementById('sl-from').value;
                const toDate   = document.getElementById('sl-to').value;
                if (!fromDate || !toDate) return;

                const reloadBtn = document.getElementById('loadBtn');
                if (reloadBtn) reloadBtn.classList.add('spinning');
                showLoading();
                try {
                    const [customersRaw, salesRaw] = await Promise.all([
                        nativeApi.call('getCustomers'),
                        nativeApi.call('getSaleReport', { start: fromDate, end: toDate }),
                    ]);

                    const customers = JSON.parse(customersRaw);
                    const sales     = Array.isArray(JSON.parse(salesRaw))
                                        ? JSON.parse(salesRaw) : [];

                    sales.forEach(s => {
                        const c = customers.find(c => c.id === s.customerId) || {};
                        s._customerName = c.name || '—';
                    });

                    renderTable(sales);
                } catch (err) {
                    console.error('Sale report error:', err);
                    showError('Failed to load sales.');
                } finally {
                    if (reloadBtn) reloadBtn.classList.remove('spinning');
                }
            }

            const PRESETS = {
                today: () => ({ from: today(),        to: today()  }),
                week:  () => ({ from: startOfWeek(),  to: today()  }),
                mtd:   () => ({ from: firstOfMonth(), to: today()  }),
            };

            function applyPreset(key) {
                const range = PRESETS[key]?.();
                if (!range) return;
                document.getElementById('sl-from').value = range.from;
                document.getElementById('sl-to').value   = range.to;
                updateSummary();
                document.querySelectorAll('.preset-pill').forEach(p =>
                    p.classList.toggle('active', p.dataset.preset === key));
            }

            function updateSummary() {
                document.getElementById('summary-from').textContent =
                    fmtDisplayDate(document.getElementById('sl-from').value);
                document.getElementById('summary-to').textContent =
                    fmtDisplayDate(document.getElementById('sl-to').value);
            }

            function setToolbarOpen(open) {
                document.getElementById('toolbar-panel').classList.toggle('open', open);
                document.getElementById('toolbar-chevron').classList.toggle('open', open);
                document.getElementById('toolbar-separator').classList.toggle('visible', open);
            }

            function init_sales_report(params = {}) {
                document.getElementById('sl-from').value = params.fromDate || firstOfMonth();
                document.getElementById('sl-to').value   = params.toDate   || today();
                updateSummary();

                document.getElementById('backBtn').addEventListener('click', () => history.back());

                document.getElementById('toolbar-summary').addEventListener('click', () => {
                    const panel = document.getElementById('toolbar-panel');
                    setToolbarOpen(!panel.classList.contains('open'));
                });

                document.getElementById('sl-from').addEventListener('change', () => {
                    updateSummary();
                    document.querySelectorAll('.preset-pill').forEach(p => p.classList.remove('active'));
                });
                document.getElementById('sl-to').addEventListener('change', () => {
                    updateSummary();
                    document.querySelectorAll('.preset-pill').forEach(p => p.classList.remove('active'));
                });

                document.querySelectorAll('.preset-pill').forEach(pill => {
                    pill.addEventListener('click', e => {
                        e.stopPropagation();
                        applyPreset(pill.dataset.preset);
                    });
                });

                document.getElementById('loadBtn').addEventListener('click', e => {
                    e.stopPropagation();
                    loadSaleReport();
                });

                setToolbarOpen(true);
                loadSaleReport();
            }

            window.init_sales_report = init_sales_report;
            window.loadSaleReport   = loadSaleReport;
        }
    }
};
