window.screenMap.trail_balance = {
    template: 'trail_balance.html',
    script: {
        expose(params) {
            function fmtCurrency(val) {
                const n = parseFloat(val);
                if (isNaN(n) || Math.abs(n) < 0.01) return '—';
                const absVal = Math.abs(n);
                return absVal.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
            }

            function fmtCurrencyWithSign(val) {
                const n = parseFloat(val);
                if (isNaN(n)) return '—';
                const absVal = Math.abs(n);
                const formatted = absVal.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
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

            function accountTypeBadgeHtml(accountType) {
                const p = palette(accountType || '—');
                return `<span class="account-type-badge"
                    style="background:${p.bg};color:${p.color};border:1px solid ${p.color}33">
                    <span style="display:inline-block;width:5px;height:5px;border-radius:50%;
                                 background:${p.color};flex-shrink:0"></span>
                    ${accountType || '—'}
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
                document.getElementById('badge-count').textContent = '0 accounts';
                document.getElementById('reportContainer').innerHTML = `
                    <div class="state-box">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                            <circle cx="12" cy="12" r="10"/>
                            <line x1="12" y1="8" x2="12" y2="12"/>
                            <line x1="12" y1="16" x2="12.01" y2="16"/>
                        </svg>
                        <p>No account data found.</p>
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

                const totalDebits  = rows.reduce((sum, row) => sum + (parseFloat(row.totalDebits)  || 0), 0);
                const totalCredits = rows.reduce((sum, row) => sum + (parseFloat(row.totalCredits) || 0), 0);
                const difference = totalDebits - totalCredits;

                const accountTypeMap = new Map();
                rows.forEach(r => {
                    const type = r.accountType || 'Other';
                    if (!accountTypeMap.has(type)) accountTypeMap.set(type, { debits: 0, credits: 0 });
                    const data = accountTypeMap.get(type);
                    data.debits  += parseFloat(r.totalDebits)  || 0;
                    data.credits += parseFloat(r.totalCredits) || 0;
                });

                const chips = [...accountTypeMap.entries()].map(([type, data]) => {
                    const net = data.debits - data.credits;
                    const pal = palette(type);
                    const isPositive = net >= 0;
                    return `
                        <div class="stat-chip" style="border-color:${pal.color}44">
                            <span class="stat-chip__label" style="color:${pal.color}99">${type}</span>
                            <span class="stat-chip__value ${isPositive ? 'positive' : 'negative'}"
                                style="color:${isPositive ? 'var(--green)' : 'var(--red)'};
                                       filter:drop-shadow(0 0 4px ${isPositive ? 'rgba(22,163,74,.5)' : 'rgba(220,38,38,.5)'})">
                                ${fmtCurrencyWithSign(net)}
                            </span>
                        </div>`;
                }).join('');

                document.getElementById('stat-strip').innerHTML = `
                    <div class="stat-chip">
                        <span class="stat-chip__label">Total Debits</span>
                        <span class="stat-chip__value positive" style="color:var(--green)">${fmtCurrency(totalDebits)}</span>
                    </div>
                    <div class="stat-chip">
                        <span class="stat-chip__label">Total Credits</span>
                        <span class="stat-chip__value" style="color:var(--purple)">${fmtCurrency(totalCredits)}</span>
                    </div>
                    <div class="stat-chip">
                        <span class="stat-chip__label">Difference</span>
                        <span class="stat-chip__value ${Math.abs(difference) < 0.01 ? 'positive' : 'negative'}"
                            style="color:${Math.abs(difference) < 0.01 ? 'var(--green)' : 'var(--red)'}">
                            ${fmtCurrencyWithSign(difference)} ${Math.abs(difference) < 0.01 ? '✓' : '✗'}
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
                    sectionTotals[type] = grouped[type].reduce((acc, row) => {
                        acc.debits  += parseFloat(row.totalDebits)  || 0;
                        acc.credits += parseFloat(row.totalCredits) || 0;
                        acc.net = acc.debits - acc.credits;
                        return acc;
                    }, { debits: 0, credits: 0, net: 0 });
                });

                const displayOrder = ['Asset', 'Liability', 'Equity', 'Revenue', 'Expense', 'Other'];
                let bodyHtml = '';
                let grandTotalDebits = 0;
                let grandTotalCredits = 0;

                displayOrder.forEach(type => {
                    const sectionRows = grouped[type];
                    if (!sectionRows || sectionRows.length === 0) return;

                    const sectionTotal = sectionTotals[type] || { debits: 0, credits: 0, net: 0 };
                    bodyHtml += `
                        <tr class="section-header">
                            <td colspan="3">${type.toUpperCase()}</td>
                            <td class="debit-cell">${fmtCurrency(sectionTotal.debits)}</td>
                            <td class="credit-cell">${fmtCurrency(sectionTotal.credits)}</td>
                            <td class="balance-cell ${sectionTotal.net >= 0 ? 'balance-positive' : 'balance-negative'}">
                                ${fmtCurrencyWithSign(sectionTotal.net)}
                            </td>
                        </tr>`;

                    sectionRows.forEach((row, i) => {
                        const delay  = Math.min(i * 12, 380);
                        const debit  = parseFloat(row.totalDebits)  || 0;
                        const credit = parseFloat(row.totalCredits) || 0;
                        const net    = debit - credit;
                        bodyHtml += `
                            <tr style="animation-delay:${delay}ms">
                                <td class="c-mono c-muted" style="font-size:11px;">${row.code || '—'}</td>
                                <td>${row.name || '—'}</td>
                                <td>${accountTypeBadgeHtml(row.accountType)}</td>
                                <td class="debit-cell">${debit > 0 ? fmtCurrency(debit) : '—'}</td>
                                <td class="credit-cell">${credit > 0 ? fmtCurrency(credit) : '—'}</td>
                                <td class="balance-cell ${net >= 0 ? 'balance-positive' : 'balance-negative'}">
                                    ${fmtCurrencyWithSign(net)}
                                </td>
                            </tr>`;
                    });

                    bodyHtml += `
                        <tr class="section-total">
                            <td colspan="3" style="text-align:right; font-weight:700;">Total ${type}</td>
                            <td class="debit-cell" style="font-weight:700;">${fmtCurrency(sectionTotal.debits)}</td>
                            <td class="credit-cell" style="font-weight:700;">${fmtCurrency(sectionTotal.credits)}</td>
                            <td class="balance-cell ${sectionTotal.net >= 0 ? 'balance-positive' : 'balance-negative'}" style="font-weight:700;">
                                ${fmtCurrencyWithSign(sectionTotal.net)}
                            </td>
                        </tr>`;

                    grandTotalDebits  += sectionTotal.debits;
                    grandTotalCredits += sectionTotal.credits;
                });

                const grandDifference = grandTotalDebits - grandTotalCredits;
                bodyHtml += `
                    <tr style="background:var(--surface2); border-top:2px solid var(--border);">
                        <td colspan="3" style="text-align:right; font-weight:700; color:var(--text-1);">GRAND TOTALS</td>
                        <td class="debit-cell" style="font-weight:700;">${fmtCurrency(grandTotalDebits)}</td>
                        <td class="credit-cell" style="font-weight:700;">${fmtCurrency(grandTotalCredits)}</td>
                        <td class="balance-cell ${Math.abs(grandDifference) < 0.01 ? 'balance-positive' : 'balance-negative'}" style="font-weight:700;">
                            ${fmtCurrencyWithSign(grandDifference)} ${Math.abs(grandDifference) < 0.01 ? '✓' : '✗'}
                        </td>
                    </tr>`;

                document.getElementById('reportContainer').innerHTML = `
                    <div class="table-scroll">
                        <table>
                            <thead>
                                <tr>
                                    <th>Code</th>
                                    <th>Account Name</th>
                                    <th>Type</th>
                                    <th class="right">Debit</th>
                                    <th class="right">Credit</th>
                                    <th class="right">Net Balance</th>
                                </tr>
                            </thead>
                            <tbody>${bodyHtml}</tbody>
                        </table>
                    </div>`;
            }

            async function loadTrialBalance() {
                const reloadBtn = document.getElementById('loadBtn');
                if (reloadBtn) reloadBtn.classList.add('spinning');
                showLoading();
                try {
                    const raw    = await nativeApi.call('getTrialBalance');
                    const parsed = raw ? JSON.parse(raw) : [];
                    const rows   = Array.isArray(parsed) ? parsed : [];
                    renderTable(rows);
                } catch (err) {
                    console.error('Trial balance error:', err);
                    showError('Failed to load trial balance.');
                } finally {
                    if (reloadBtn) reloadBtn.classList.remove('spinning');
                }
            }

            function init_trail_balance() {
                document.getElementById('backBtn').addEventListener('click', () => history.back());
                document.getElementById('toolbar-summary').removeAttribute('onclick');
                document.getElementById('loadBtn').addEventListener('click', (e) => {
                    e.stopPropagation();
                    loadTrialBalance();
                });
                loadTrialBalance();
            }

            window.init_trail_balance = init_trail_balance;
        }
    }
};
