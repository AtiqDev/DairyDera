window.screenMap.transactions = {
    template: 'transactions.html',
    script: {
        expose(params) {
            function today()        { return new Date().toISOString().slice(0, 10); }
            function firstOfMonth() {
                const n = new Date();
                return new Date(n.getFullYear(), n.getMonth(), 1).toISOString().slice(0, 10);
            }
            function fmtDisplayDate(iso) {
                if (!iso) return '—';
                const [y, m, d] = iso.split('-');
                const months = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];
                return `${months[parseInt(m,10)-1]} ${parseInt(d,10)}, ${y}`;
            }

            function fmtQty(val) {
                const n = parseFloat(val);
                return isNaN(n) ? '—' : n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
            }
            function fmtAmount(val) {
                const n = parseFloat(val);
                return isNaN(n) ? '—' : n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
            }
            function fmtDateTime(raw) {
                if (!raw) return '—';
                const parts = raw.trim().split(' ');
                const datePart = parts[0] || '';
                const timePart = parts[1] || '';
                if (!timePart) return datePart;
                const [hStr, mStr] = timePart.split(':');
                let h = parseInt(hStr, 10);
                const m = mStr || '00';
                const ampm = h >= 12 ? 'PM' : 'AM';
                h = h % 12 || 12;
                return `${datePart}  ${h}:${m} ${ampm}`;
            }

            const PALETTES = [
                { bg: 'rgba(37,99,235,.09)',   color: '#2563eb' },
                { bg: 'rgba(22,163,74,.09)',   color: '#16a34a' },
                { bg: 'rgba(217,119,6,.09)',   color: '#d97706' },
                { bg: 'rgba(124,58,237,.09)',  color: '#7c3aed' },
                { bg: 'rgba(13,148,136,.09)',  color: '#0d9488' },
                { bg: 'rgba(220,38,38,.09)',   color: '#dc2626' },
                { bg: 'rgba(75,0,130,.09)',    color: '#4b0082' },
            ];
            const colorMap = {};
            let colorIdx = 0;
            function palette(key) {
                if (!colorMap[key]) colorMap[key] = PALETTES[colorIdx++ % PALETTES.length];
                return colorMap[key];
            }
            function refBadgeHtml(refType) {
                const p = palette(refType || '—');
                return `<span class="ref-badge"
                    style="background:${p.bg};color:${p.color};border:1px solid ${p.color}33">
                    <span style="display:inline-block;width:5px;height:5px;border-radius:50%;
                                 background:${p.color};flex-shrink:0"></span>
                    ${refType || '—'}
                </span>`;
            }

            function dirClass(txnType) {
                const t = (txnType || '').toLowerCase();
                if (t === 'in')  return 'in';
                if (t === 'out') return 'out';
                return 'other';
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
                            <circle cx="12" cy="12" r="10"/>
                            <line x1="12" y1="8" x2="12" y2="12"/>
                            <line x1="12" y1="16" x2="12.01" y2="16"/>
                        </svg>
                        <p>No transactions found for this period.</p>
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
                    `${count} row${count !== 1 ? 's' : ''}`;

                const firstKeys      = rows.length ? Object.keys(rows[0]) : [];
                const keyBadgeName   = firstKeys.find(k => k.toLowerCase() === 'badgename');
                const keyBadgeValues = firstKeys.find(k => k.toLowerCase() === 'badgevalues');

                if (!keyBadgeName || !keyBadgeValues) {
                    document.getElementById('stat-strip').innerHTML = '';
                    return;
                }

                const badgeMap = new Map();
                rows.forEach(r => {
                    const name = r[keyBadgeName] || '—';
                    badgeMap.set(name, (badgeMap.get(name) || 0) + (parseFloat(r[keyBadgeValues]) || 0));
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
                        <span class="stat-chip__label">Net Qty</span>
                        <span class="stat-chip__value green">${fmtAmount(grandTotal)}</span>
                    </div>
                    ${chips}`;
            }

            function renderTable(rows) {
                if (!rows.length) { showEmpty(); return; }
                renderStats(rows);

                const bodyHtml = rows.map((r, i) => {
                    const dir   = dirClass(r.transactionType);
                    const delay = Math.min(i * 12, 380);
                    return `
                        <tr style="animation-delay:${delay}ms">
                            <td class="c-bold">${r.productName ?? '—'}</td>
                            <td>${refBadgeHtml(r.referenceType)}</td>
                            <td><span class="dir-dot ${dir}"></span></td>
                            <td class="qty-cell ${dir}">
                                ${fmtQty(r.quantity)}
                                <span class="qty-unit">${r.unitName ?? ''}</span>
                            </td>
                            <td class="c-mono c-nowrap" style="color:var(--text-2);font-size:11px;line-height:1.4">
                                ${fmtDateTime(r.transactionDate)}
                            </td>
                        </tr>`;
                }).join('');

                document.getElementById('reportContainer').innerHTML = `
                    <div class="table-scroll">
                        <table>
                            <thead>
                                <tr>
                                    <th>Product</th>
                                    <th>Ref Type</th>
                                    <th>D</th>
                                    <th class="right">Qty / Unit</th>
                                    <th>Date</th>
                                </tr>
                            </thead>
                            <tbody>${bodyHtml}</tbody>
                        </table>
                    </div>`;
            }

            async function loadTransactions() {
                const fromDate = document.getElementById('tx-from').value;
                const toDate   = document.getElementById('tx-to').value;
                if (!fromDate || !toDate) return;

                const reloadBtn = document.getElementById('loadBtn');
                if (reloadBtn) reloadBtn.classList.add('spinning');
                showLoading();
                try {
                    const raw    = await nativeApi.call('getTransactionReport', { fromDate, toDate });
                    const parsed = raw ? JSON.parse(raw) : [];
                    const rows   = Array.isArray(parsed) ? parsed : [];
                    renderTable(rows);
                } catch (err) {
                    console.error('Transaction report error:', err);
                    showError('Failed to load transactions.');
                } finally {
                    if (reloadBtn) reloadBtn.classList.remove('spinning');
                }
            }

            function isoDate(d) { return d.toISOString().slice(0, 10); }
            function startOfWeek() {
                const d = new Date(); d.setDate(d.getDate() - d.getDay()); return isoDate(d);
            }
            const PRESETS = {
                today: () => ({ from: today(),        to: today()        }),
                week:  () => ({ from: startOfWeek(),  to: today()        }),
                mtd:   () => ({ from: firstOfMonth(), to: today()        }),
            };

            function applyPreset(key) {
                const range = PRESETS[key]?.();
                if (!range) return;
                document.getElementById('tx-from').value = range.from;
                document.getElementById('tx-to').value   = range.to;
                updateSummary();
                document.querySelectorAll('.preset-pill').forEach(p =>
                    p.classList.toggle('active', p.dataset.preset === key));
            }

            function updateSummary() {
                document.getElementById('summary-from').textContent =
                    fmtDisplayDate(document.getElementById('tx-from').value);
                document.getElementById('summary-to').textContent =
                    fmtDisplayDate(document.getElementById('tx-to').value);
            }

            function setToolbarOpen(open) {
                document.getElementById('toolbar-panel').classList.toggle('open', open);
                document.getElementById('toolbar-chevron').classList.toggle('open', open);
                document.getElementById('toolbar-separator').classList.toggle('visible', open);
            }

            function init_transactions(params = {}) {
                document.getElementById('tx-from').value = params.fromDate || firstOfMonth();
                document.getElementById('tx-to').value   = params.toDate   || today();
                updateSummary();

                document.getElementById('backBtn').addEventListener('click', () => history.back());

                document.getElementById('toolbar-summary').addEventListener('click', () => {
                    const panel = document.getElementById('toolbar-panel');
                    setToolbarOpen(!panel.classList.contains('open'));
                });

                document.getElementById('tx-from').addEventListener('change', () => {
                    updateSummary();
                    document.querySelectorAll('.preset-pill').forEach(p => p.classList.remove('active'));
                });
                document.getElementById('tx-to').addEventListener('change', () => {
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
                    loadTransactions();
                });

                setToolbarOpen(true);
                loadTransactions();
            }

            window.init_transactions = init_transactions;
        }
    }
};
