window.screenMap.balance_sheet = {
    template: 'balance_sheet.html',
    script: {
        expose(params) {
            function today() { return new Date().toISOString().slice(0, 10); }
            function monthEnd() {
                const now = new Date();
                const lastDay = new Date(now.getFullYear(), now.getMonth() + 1, 0);
                return lastDay.toISOString().slice(0, 10);
            }
            function yearEnd() {
                const now = new Date();
                return `${now.getFullYear()}-12-31`;
            }
            function fmtDisplayDate(iso) {
                if (!iso) return '—';
                const [y, m, d] = iso.split('-');
                const months = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];
                return `${months[parseInt(m,10)-1]} ${parseInt(d,10)}, ${y}`;
            }

            function fmtAmount(val) {
                const n = parseFloat(val);
                if (isNaN(n)) return '—';
                const absVal = Math.abs(n);
                const formatted = absVal.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
                return n < 0 ? `(${formatted})` : formatted;
            }

            function fmtCurrency(val) {
                const n = parseFloat(val);
                if (isNaN(n)) return '—';
                const absVal = Math.abs(n);
                const formatted = absVal.toLocaleString('en-US', {
                    style: 'currency', currency: 'USD',
                    minimumFractionDigits: 2, maximumFractionDigits: 2
                });
                return n < 0 ? `(${formatted})` : formatted;
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

            function showLoading() {
                document.getElementById('stat-strip').innerHTML = '';
                document.getElementById('badge-count').textContent = '—';
                document.getElementById('reportContainer').innerHTML =
                    `<div class="state-box"><div class="spinner"></div><p>Loading…</p></div>`;
            }
            function showEmpty() {
                document.getElementById('stat-strip').innerHTML = '';
                document.getElementById('badge-count').textContent = '0 accounts';
                document.getElementById('reportContainer').innerHTML = `
                    <div class="state-box">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                            <circle cx="12" cy="12" r="10"/>
                            <line x1="12" y1="8" x2="12" y2="12"/>
                            <line x1="12" y1="16" x2="12.01" y2="16"/>
                        </svg>
                        <p>No account data found for this date.</p>
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
                    `${count} account${count !== 1 ? 's' : ''}`;

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

                const totalAssets      = badgeMap.get('Asset')     || 0;
                const totalLiabilities = badgeMap.get('Liability')  || 0;
                const totalEquity      = badgeMap.get('Equity')     || 0;
                const balanceCheck     = totalAssets - (totalLiabilities + totalEquity);

                const chips = [...badgeMap.entries()].map(([name, total]) => {
                    const pal = palette(name);
                    const isPositive = total >= 0;
                    return `
                        <div class="stat-chip" style="border-color:${pal.color}44">
                            <span class="stat-chip__label" style="color:${pal.color}99">${name}</span>
                            <span class="stat-chip__value ${isPositive ? 'positive' : 'negative'}"
                                style="color:${isPositive ? 'var(--green)' : 'var(--red)'};
                                       filter:drop-shadow(0 0 4px ${isPositive ? 'rgba(22,163,74,.5)' : 'rgba(220,38,38,.5)'})">
                                ${fmtCurrency(total)}
                            </span>
                        </div>`;
                }).join('');

                document.getElementById('stat-strip').innerHTML = `
                    <div class="stat-chip">
                        <span class="stat-chip__label">Balanced?</span>
                        <span class="stat-chip__value ${Math.abs(balanceCheck) < 0.01 ? 'positive' : 'negative'}"
                            style="color:${Math.abs(balanceCheck) < 0.01 ? 'var(--green)' : 'var(--red)'}">
                            ${Math.abs(balanceCheck) < 0.01 ? '✓ Balanced' : '✗ Imbalance'}
                        </span>
                    </div>
                    ${chips}`;
            }

            function renderTable(rows) {
                if (!rows.length) { showEmpty(); return; }
                renderStats(rows);

                const grouped = {};
                rows.forEach(row => {
                    const type = row.accountType || 'Other';
                    if (!grouped[type]) grouped[type] = [];
                    grouped[type].push(row);
                });

                const sectionTotals = {};
                Object.keys(grouped).forEach(type => {
                    sectionTotals[type] = grouped[type].reduce((sum, row) => sum + (parseFloat(row.balance) || 0), 0);
                });

                const displayOrder = ['Asset', 'Liability', 'Equity'];
                let bodyHtml = '';
                let overallAssets = 0;
                let overallLiabilitiesEquity = 0;

                displayOrder.forEach(type => {
                    const sectionRows = grouped[type];
                    if (!sectionRows || sectionRows.length === 0) return;

                    const sectionTotal = sectionTotals[type] || 0;
                    bodyHtml += `
                        <tr class="section-header">
                            <td colspan="2">${type.toUpperCase()}</td>
                            <td class="balance-cell ${sectionTotal >= 0 ? 'balance-positive' : 'balance-negative'}">
                                ${fmtCurrency(sectionTotal)}
                            </td>
                        </tr>`;

                    sectionRows.forEach((row, i) => {
                        const delay   = Math.min(i * 12, 380);
                        const balance = parseFloat(row.balance) || 0;
                        bodyHtml += `
                            <tr style="animation-delay:${delay}ms">
                                <td class="c-mono c-muted" style="font-size:11px;">${row.code || '—'}</td>
                                <td>${row.name || '—'}</td>
                                <td class="balance-cell ${balance >= 0 ? 'balance-positive' : 'balance-negative'}">
                                    ${fmtCurrency(balance)}
                                </td>
                            </tr>`;
                    });

                    bodyHtml += `
                        <tr class="section-total">
                            <td colspan="2" style="text-align:right; font-weight:700;">Total ${type}</td>
                            <td class="balance-cell ${sectionTotal >= 0 ? 'balance-positive' : 'balance-negative'}" style="font-weight:700;">
                                ${fmtCurrency(sectionTotal)}
                            </td>
                        </tr>`;

                    if (type === 'Asset') overallAssets += sectionTotal;
                    else overallLiabilitiesEquity += sectionTotal;
                });

                const imbalance = overallAssets - overallLiabilitiesEquity;
                bodyHtml += `
                    <tr style="background:var(--surface2); border-top:2px solid var(--border);">
                        <td colspan="2" style="text-align:right; font-weight:700; color:var(--text-1);">
                            Assets = Liabilities + Equity
                        </td>
                        <td class="balance-cell ${Math.abs(imbalance) < 0.01 ? 'balance-positive' : 'balance-negative'}" style="font-weight:700;">
                            ${fmtCurrency(imbalance)} ${Math.abs(imbalance) < 0.01 ? '✓' : '✗'}
                        </td>
                    </tr>`;

                document.getElementById('reportContainer').innerHTML = `
                    <div class="table-scroll">
                        <table>
                            <thead>
                                <tr>
                                    <th>Code</th>
                                    <th>Account Name</th>
                                    <th class="right">Balance</th>
                                </tr>
                            </thead>
                            <tbody>${bodyHtml}</tbody>
                        </table>
                    </div>`;
            }

            async function loadBalanceSheet() {
                const asOfDate = document.getElementById('as-of-date').value;
                if (!asOfDate) return;

                const reloadBtn = document.getElementById('loadBtn');
                if (reloadBtn) reloadBtn.classList.add('spinning');
                showLoading();
                try {
                    const raw    = await nativeApi.call('getBalanceSheet', { asOfDate });
                    const parsed = raw ? JSON.parse(raw) : [];
                    const rows   = Array.isArray(parsed) ? parsed : [];
                    renderTable(rows);
                } catch (err) {
                    console.error('Balance sheet error:', err);
                    showError('Failed to load balance sheet.');
                } finally {
                    if (reloadBtn) reloadBtn.classList.remove('spinning');
                }
            }

            const PRESETS = {
                today:    () => today(),
                monthEnd: () => monthEnd(),
                yearEnd:  () => yearEnd()
            };

            function applyPreset(key) {
                const date = PRESETS[key]?.();
                if (!date) return;
                document.getElementById('as-of-date').value = date;
                updateSummary();
                document.querySelectorAll('.preset-pill').forEach(p =>
                    p.classList.toggle('active', p.dataset.preset === key));
            }

            function updateSummary() {
                document.getElementById('summary-as-of').textContent =
                    fmtDisplayDate(document.getElementById('as-of-date').value);
            }

            function setToolbarOpen(open) {
                document.getElementById('toolbar-panel').classList.toggle('open', open);
                document.getElementById('toolbar-chevron').classList.toggle('open', open);
                document.getElementById('toolbar-separator').classList.toggle('visible', open);
            }

            function init_balance_sheet(params = {}) {
                document.getElementById('as-of-date').value = params.asOfDate || monthEnd();
                updateSummary();

                document.getElementById('backBtn').addEventListener('click', () => history.back());

                document.getElementById('toolbar-summary').addEventListener('click', () => {
                    const panel = document.getElementById('toolbar-panel');
                    setToolbarOpen(!panel.classList.contains('open'));
                });

                document.getElementById('as-of-date').addEventListener('change', () => {
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
                    loadBalanceSheet();
                });

                setToolbarOpen(true);
                loadBalanceSheet();
            }

            window.init_balance_sheet = init_balance_sheet;
            window.loadBalanceSheet   = loadBalanceSheet;
        }
    }
};
