window.screenMap.journal_report = {
    template: 'journal_report.html',
    script: {
        expose(params) {
            function today()        { return new Date().toISOString().slice(0, 10); }
            function firstOfMonth() {
                const n = new Date();
                return new Date(n.getFullYear(), n.getMonth(), 1).toISOString().slice(0, 10);
            }

            function fmtAmount(val) {
                const n = parseFloat(val);
                return isNaN(n) ? '—'
                    : n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
            }

            function parseAccount(raw) {
                if (!raw) return { name: '—', type: '' };
                const m = raw.match(/^(.+?)\s*\(([^)]+)\)\s*$/);
                return m ? { name: m[1].trim(), type: m[2].trim() } : { name: raw, type: '' };
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
                    <span style="display:inline-block;width:5px;height:5px;border-radius:50%;background:${p.color};flex-shrink:0"></span>
                    ${refType || '—'}
                </span>`;
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
                            <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/>
                            <polyline points="14 2 14 8 20 8"/>
                            <line x1="16" y1="13" x2="8" y2="13"/>
                            <line x1="16" y1="17" x2="8" y2="17"/>
                        </svg>
                        <p>No journal entries found for this period.</p>
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

            function renderStats(entries) {
                const count = entries.length;
                document.getElementById('badge-count').textContent =
                    `${count} entr${count !== 1 ? 'ies' : 'y'}`;

                const firstKeys      = entries.length ? Object.keys(entries[0]) : [];
                const keyBadgeName   = firstKeys.find(k => k.toLowerCase() === 'badgename');
                const keyBadgeValues = firstKeys.find(k => k.toLowerCase() === 'badgevalues');

                if (!keyBadgeName || !keyBadgeValues) {
                    document.getElementById('stat-strip').innerHTML = '';
                    return;
                }

                const badgeMap = new Map();
                entries.forEach(e => {
                    const name = e[keyBadgeName] || '-';
                    badgeMap.set(name, (badgeMap.get(name) || 0) + (parseFloat(e[keyBadgeValues]) || 0));
                });

                const grandTotal = [...badgeMap.values()].reduce((s, v) => s + v, 0);

                const chips = [...badgeMap.entries()].map(([name, total]) => {
                    const pal = palette(name);
                    return `
                        <div class="stat-chip" style="border-color:${pal.color}33">
                            <span class="stat-chip__label" style="color:${pal.color}">${name}</span>
                            <span class="stat-chip__value" style="color:${pal.color}">${fmtAmount(total)}</span>
                        </div>`;
                }).join('');

                document.getElementById('stat-strip').innerHTML = `
                    <div class="stat-chip">
                        <span class="stat-chip__label">Total</span>
                        <span class="stat-chip__value green">${fmtAmount(grandTotal)}</span>
                    </div>
                    ${chips}`;
            }

            function renderTable(entries) {
                if (!entries.length) { showEmpty(); return; }
                renderStats(entries);

                const grandTotal = entries.reduce((s, e) => s + (parseFloat(e.amount) || 0), 0);

                const bodyHtml = entries.map((e, i) => {
                    const dr   = parseAccount(e.debitAccount);
                    const cr   = parseAccount(e.creditAccount);
                    const delay = Math.min(i * 12, 380);
                    const desc  = (e.description || '-').replace(/"/g, '&quot;');

                    return `
                        <tr style="animation-delay:${delay}ms">
                            <td class="c-nowrap"><span class="id-chip">#${e.id}</span></td>
                            <td class="c-mono c-nowrap" style="color:var(--text-2)">${e.date ?? '-'}</td>
                            <td>${refBadgeHtml(e.referenceType)}</td>
                            <td>
                                <div class="account-pair">
                                    <div class="account-row">
                                        <span class="dir-tag dr">Dr</span>
                                        <span class="acc-name">${dr.name}</span>
                                        ${dr.type ? `<span class="acc-type">(${dr.type})</span>` : ''}
                                    </div>
                                    <div class="account-row">
                                        <span class="dir-tag cr">Cr</span>
                                        <span class="acc-name">${cr.name}</span>
                                        ${cr.type ? `<span class="acc-type">(${cr.type})</span>` : ''}
                                    </div>
                                </div>
                            </td>
                            <td class="c-right c-mono c-green c-nowrap">${fmtAmount(e.amount)}</td>
                            <td><div class="desc-cell" title="${desc}">${e.description || '-'}</div></td>
                        </tr>`;
                }).join('');

                document.getElementById('reportContainer').innerHTML = `
                    <div class="table-scroll">
                        <table>
                            <thead>
                                <tr>
                                    <th>ID</th>
                                    <th>Date</th>
                                    <th>Ref Type</th>
                                    <th>Accounts (Dr / Cr)</th>
                                    <th class="right">Amount</th>
                                    <th>Description</th>
                                </tr>
                            </thead>
                            <tbody>${bodyHtml}</tbody>
                            <tfoot>
                                <tr>
                                    <td colspan="4" style="text-align:right;color:var(--text-2);font-size:11px;letter-spacing:.06em;text-transform:uppercase;">
                                        Grand Total
                                    </td>
                                    <td class="c-right c-green" style="font-family:var(--mono)">
                                        ${fmtAmount(grandTotal)}
                                    </td>
                                    <td></td>
                                </tr>
                            </tfoot>
                        </table>
                    </div>`;
            }

            async function loadJournalEntries() {
                const fromDate = document.getElementById('je-from').value;
                const toDate   = document.getElementById('je-to').value;
                if (!fromDate || !toDate) return;

                const reloadBtn = document.getElementById('loadBtn');
                if (reloadBtn) reloadBtn.classList.add('spinning');
                showLoading();
                try {
                    const raw     = await nativeApi.call('getJournalEntryReport', { fromDate, toDate });
                    const parsed  = raw ? JSON.parse(raw) : [];
                    const entries = Array.isArray(parsed) ? parsed : [];
                    renderTable(entries);
                } catch (err) {
                    console.error('Journal report error:', err);
                    showError('Failed to load journal entries.');
                } finally {
                    if (reloadBtn) reloadBtn.classList.remove('spinning');
                }
            }

            function fmtDisplayDate(iso) {
                if (!iso) return '—';
                const [y, m, d] = iso.split('-');
                const months = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];
                return `${months[parseInt(m,10)-1]} ${parseInt(d,10)}, ${y}`;
            }

            function updateSummary() {
                const from = document.getElementById('je-from').value;
                const to   = document.getElementById('je-to').value;
                document.getElementById('summary-from').textContent = fmtDisplayDate(from);
                document.getElementById('summary-to').textContent   = fmtDisplayDate(to);
            }

            function setToolbarOpen(open) {
                document.getElementById('toolbar-panel').classList.toggle('open', open);
                document.getElementById('toolbar-chevron').classList.toggle('open', open);
                document.getElementById('toolbar-separator').classList.toggle('visible', open);
            }

            function init_journal_report() {
                document.getElementById('je-from').value = firstOfMonth();
                document.getElementById('je-to').value   = today();
                updateSummary();

                document.getElementById('backBtn').addEventListener('click', () => navigate());

                document.getElementById('toolbar-summary').addEventListener('click', () => {
                    const panel = document.getElementById('toolbar-panel');
                    setToolbarOpen(!panel.classList.contains('open'));
                });

                document.getElementById('je-from').addEventListener('change', updateSummary);
                document.getElementById('je-to').addEventListener('change',   updateSummary);

                document.getElementById('loadBtn').addEventListener('click', (e) => {
                    e.stopPropagation();
                    loadJournalEntries();
                });

                setToolbarOpen(true);
                loadJournalEntries();
            }

            window.init_journal_report = init_journal_report;
            window.loadJournalEntries  = loadJournalEntries;
        }
    }
};
