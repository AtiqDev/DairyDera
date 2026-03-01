window.screenMap.purchase_report = {
    template: 'purchase_report.html',
    script: {
        expose(params) {
            /* ── PALETTE ── */
            const PALETTES = [
                { bg: 'rgba(13,148,136,.09)',  color: '#0d9488' },
                { bg: 'rgba(37,99,235,.09)',   color: '#2563eb' },
                { bg: 'rgba(124,58,237,.09)',  color: '#7c3aed' },
                { bg: 'rgba(217,119,6,.09)',   color: '#d97706' }
            ];
            const colorMap = {};
            let colorIdx = 0;
            function palette(key) {
                if (!colorMap[key]) colorMap[key] = PALETTES[colorIdx++ % PALETTES.length];
                return colorMap[key];
            }

            function fmtAmount(val) {
                const n = parseFloat(val);
                return isNaN(n) ? '—' : n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
            }

            function getPresetDates(preset) {
                const now = new Date();
                let start = new Date();
                let end = new Date();

                if (preset === 'today') {
                    // Same day
                } else if (preset === 'week') {
                    const day = now.getDay();
                    const diff = now.getDate() - day + (day === 0 ? -6 : 1);
                    start.setDate(diff);
                } else if (preset === 'mtd') {
                    start = new Date(now.getFullYear(), now.getMonth(), 1);
                }

                return {
                    start: start.toISOString().slice(0, 10),
                    end: end.toISOString().slice(0, 10)
                };
            }

            function renderStats(rows) {
                const badgeMap = new Map();
                rows.forEach(r => {
                    const name = r.badgeName || '—';
                    badgeMap.set(name, (badgeMap.get(name) || 0) + (parseFloat(r.badgeValues) || 0));
                });

                const grandTotal = [...badgeMap.values()].reduce((s, v) => s + v, 0);
                const chips = [...badgeMap.entries()].map(([name, total]) => {
                    const pal = palette(name);
                    return `
                        <div class="stat-chip" style="border-color:${pal.color}44">
                            <span class="stat-chip__label" style="color:${pal.color}99">${name}</span>
                            <span class="stat-chip__value" style="color:${pal.color}">${fmtAmount(total)}</span>
                        </div>`;
                }).join('');

                document.getElementById('stat-strip').innerHTML = `
                    <div class="stat-chip">
                        <span class="stat-chip__label">Total Cost</span>
                        <span class="stat-chip__value brand">${fmtAmount(grandTotal)}</span>
                    </div>${chips}`;

                document.getElementById('badge-count').textContent = `${rows.length} records`;
            }

            function renderTable(rows) {
                if (!rows.length) {
                    document.getElementById('reportContainer').innerHTML = '<div style="padding:50px;text-align:center;color:var(--text-3)">No purchases found.</div>';
                    document.getElementById('stat-strip').innerHTML = '';
                    return;
                }
                renderStats(rows);

                const bodyHtml = rows.map((r, i) => {
                    const p = palette(r.purchaseEvent);
                    return `
                        <tr style="animation-delay:${Math.min(i * 12, 380)}ms">
                            <td>
                                <div class="c-bold">${r.productName}</div>
                                <div style="font-size:11px;color:var(--text-2)">${r.supplier}</div>
                            </td>
                            <td>
                                <span class="ref-badge" style="background:${p.bg};color:${p.color};border:1px solid ${p.color}33">
                                    <span class="dir-dot" style="background:${p.color}"></span>&nbsp;${r.purchaseEvent}
                                </span>
                            </td>
                            <td class="qty-cell">
                                ${fmtAmount(r.quantity)}
                                <span class="qty-unit">${r.unitName || ''}</span>
                            </td>
                            <td class="c-mono c-right c-bold" style="color:var(--text-1)">
                                ${fmtAmount(r.lineAmount)}
                            </td>
                        </tr>`;
                }).join('');

                document.getElementById('reportContainer').innerHTML = `
                    <div class="table-scroll">
                        <table>
                            <thead>
                                <tr>
                                    <th>Item / Supplier</th>
                                    <th>Status</th>
                                    <th class="c-right">Qty</th>
                                    <th class="c-right">Amount</th>
                                </tr>
                            </thead>
                            <tbody>${bodyHtml}</tbody>
                        </table>
                    </div>`;
            }

            async function loadPurchaseReport() {
                const start = document.getElementById('tx-from').value;
                const end = document.getElementById('tx-to').value;
                const btn = document.getElementById('loadBtn');
                btn.classList.add('spinning');
                try {
                    const raw = await nativeApi.call('queryPurchaseByDateReport', { start, end });
                    renderTable(JSON.parse(raw || "[]"));
                } catch (e) {
                    console.error(e);
                } finally {
                    btn.classList.remove('spinning');
                }
            }

            function init_purchase_report(params = {}) {
                const mtd = getPresetDates('mtd');
                document.getElementById('tx-from').value = params.start || mtd.start;
                document.getElementById('tx-to').value   = params.end   || mtd.end;

                const updateSummary = () => {
                    document.getElementById('summary-from').textContent = document.getElementById('tx-from').value;
                    document.getElementById('summary-to').textContent = document.getElementById('tx-to').value;
                };
                updateSummary();

                document.getElementById('toolbar-summary').onclick = () => {
                    const isOpen = document.getElementById('toolbar-panel').classList.toggle('open');
                    document.getElementById('toolbar-chevron').classList.toggle('open', isOpen);
                    document.getElementById('toolbar-separator').classList.toggle('visible', isOpen);
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
                        loadPurchaseReport();
                    };
                });

                const handleManualChange = () => {
                    document.querySelectorAll('.preset-pill').forEach(p => p.classList.remove('active'));
                    updateSummary();
                };
                document.getElementById('tx-from').onchange = handleManualChange;
                document.getElementById('tx-to').onchange = handleManualChange;

                document.getElementById('backBtn').onclick = () => history.back();
                document.getElementById('loadBtn').onclick = (e) => { e.stopPropagation(); loadPurchaseReport(); };

                loadPurchaseReport();
            }

            window.init_purchase_report = init_purchase_report;
            window.loadPurchaseReport = loadPurchaseReport;
        }
    }
};
