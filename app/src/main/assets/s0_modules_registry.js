window.screenMap.modules_registry = {
    template: 'modules_registry.html',
    script: {

        // ── DataBridge ─────────────────────────────────────────
        DB: {
            async _call(action, payload = {}) {
                try {
                    const raw = await nativeApi.call(action, payload);
                    return raw ? JSON.parse(raw) : null;
                } catch (err) {
                    console.error('[ModulesRegistry]', action, err);
                    return null;
                }
            },
            getModules()                     { return this._call('getModules'); },
            getModuleDetail(moduleId)        { return this._call('getModuleDetail', { moduleId }); },
            getMappingsForTxnType(txnTypeId) { return this._call('getMappingsForTxnType', { txnTypeId }); },
            saveMapping(payload)             { return this._call('saveMapping', payload); },
            deleteMapping(id)                { return this._call('deleteMapping', { id }); },
            getUnmappedSummary()             { return this._call('getUnmappedSummary'); },
            getAccounts()                    { return this._call('getAccounts'); },
        },

        // ── Module colour config ────────────────────────────────
        MODULE_CONFIG: {
            'Procurement': { icon: 'fa-shopping-cart', bg: '#3d84f5' },
            'Sales':       { icon: 'fa-receipt',       bg: '#20c997' },
            'Production':  { icon: 'fa-industry',      bg: '#f06a39' },
            'Expenses':    { icon: 'fa-file-invoice-dollar', bg: '#a855f7' },
        },

        // ── App ─────────────────────────────────────────────────
        App: class {
            constructor(DB, MODULE_CONFIG) {
                this.DB = DB;
                this.MODULE_CONFIG = MODULE_CONFIG;
                this.accounts = [];
                this.selectedModule  = null;   // { id, name }
                this.selectedTxnType = null;   // { txnTypeId, txnTypeName }
                this.currentMappings = [];
            }

            // ── Lifecycle ───────────────────────────────────────

            async init() {
                // Offset fixed panels below the persistent app header
                const headerH = (document.querySelector('header')?.offsetHeight || 0) + 'px';
                ['mr-panel-a', 'mr-panel-b', 'mr-panel-c', 'mr-overlay'].forEach(id => {
                    const el = document.getElementById(id);
                    if (el) el.style.top = headerH;
                });

                // Bind static controls
                document.getElementById('mr-back-b').addEventListener('click',   () => this._slideToPanel('a'));
                document.getElementById('mr-back-c').addEventListener('click',   () => this._slideToPanel('b'));
                document.getElementById('mr-fab').addEventListener('click',      () => this._openSheet(null));
                document.getElementById('mr-overlay').addEventListener('click',  () => this._closeSheet());
                document.getElementById('mr-form-cancel').addEventListener('click', () => this._closeSheet());
                document.getElementById('mr-form-save').addEventListener('click',   () => this._saveMapping());

                // Preload accounts for dropdowns
                this.accounts = await this.DB.getAccounts() || [];
                this._populateAccountSelects();

                // Load panels
                await this._loadPanelA();
            }

            // ── Panel navigation ────────────────────────────────

            _slideToPanel(id) {
                const order = { a: 0, b: 1, c: 2 };
                const target = order[id];
                ['a', 'b', 'c'].forEach(p => {
                    const el = document.getElementById('mr-panel-' + p);
                    if (!el) return;
                    el.classList.toggle('slide-right', order[p] > target);
                });
            }

            // ── Panel A ─────────────────────────────────────────

            async _loadPanelA() {
                const [modules, unmapped] = await Promise.all([
                    this.DB.getModules(),
                    this.DB.getUnmappedSummary()
                ]);
                this._renderModuleGrid(modules || []);
                this._renderUnmapped(unmapped || []);
            }

            _renderModuleGrid(modules) {
                const grid = document.getElementById('mr-module-grid');
                if (!modules.length) {
                    grid.innerHTML = '<p class="text-muted text-center p-4">No modules found.</p>';
                    return;
                }
                grid.innerHTML = modules.map(m => {
                    const cfg      = this.MODULE_CONFIG[m.name] || { icon: 'fa-cubes', bg: '#6c757d' };
                    const pct      = m.txnTypeCount > 0 ? Math.round((m.mappedCount / m.txnTypeCount) * 100) : 0;
                    const fillColor = pct === 100 ? '#198754' : pct > 0 ? '#fd7e14' : '#dc3545';
                    const labelColor = pct === 100 ? 'text-success' : pct > 0 ? 'text-warning' : 'text-danger';
                    return `
                      <div class="mr-module-card"
                           data-mid="${m.id}" data-mname="${m.name}">
                        <div class="mr-module-icon" style="background:${cfg.bg}">
                          <i class="fas ${cfg.icon}"></i>
                        </div>
                        <div class="mr-module-name">${m.name}</div>
                        <div class="mr-progress-bar">
                          <div class="mr-progress-fill" style="width:${pct}%;background:${fillColor}"></div>
                        </div>
                        <div class="mr-progress-label ${labelColor}">
                          ${m.mappedCount} / ${m.txnTypeCount} mapped
                        </div>
                      </div>
                    `;
                }).join('');

                grid.querySelectorAll('.mr-module-card').forEach(card => {
                    card.addEventListener('click', () => {
                        this.selectedModule = { id: +card.dataset.mid, name: card.dataset.mname };
                        this._loadPanelB(this.selectedModule.id, this.selectedModule.name);
                    });
                });
            }

            _renderUnmapped(rows) {
                const body = document.getElementById('mr-unmapped-body');
                if (!rows.length) {
                    body.innerHTML = `
                      <div class="d-flex align-items-center gap-2 p-2 text-success">
                        <i class="fas fa-check-circle"></i>
                        <span style="font-size:0.85rem">All transaction types are configured.</span>
                      </div>`;
                    document.querySelector('.mr-unmapped-card .card-header').className =
                        'card-header bg-success text-white d-flex align-items-center gap-2';
                    return;
                }
                body.innerHTML = rows.map(r => `
                  <div class="mr-unmapped-row">
                    <span>${r.txnTypeName}</span>
                    <span class="badge bg-secondary">${r.moduleName}</span>
                  </div>
                `).join('');
            }

            // ── Panel B ─────────────────────────────────────────

            async _loadPanelB(moduleId, moduleName) {
                document.getElementById('mr-panel-b-title').textContent = moduleName;
                const list = document.getElementById('mr-txntype-list');
                list.innerHTML = '<div class="text-muted p-4 text-center">Loading…</div>';
                this._slideToPanel('b');

                const types = await this.DB.getModuleDetail(moduleId) || [];
                if (!types.length) {
                    list.innerHTML = '<div class="text-muted p-4 text-center">No transaction types in this module.</div>';
                    return;
                }
                list.innerHTML = types.map(t => {
                    const hasMap     = t.mappingCount > 0;
                    const badgeCls   = hasMap ? 'bg-success' : 'bg-danger';
                    const badgeLabel = hasMap ? `${t.mappingCount} rule${t.mappingCount > 1 ? 's' : ''}` : 'No rules';
                    return `
                      <div class="mr-txntype-row"
                           data-tid="${t.txnTypeId}" data-tname="${t.txnTypeName}">
                        <span class="mr-txntype-name">${t.txnTypeName}</span>
                        <span class="mr-txntype-badge ${badgeCls} text-white">${badgeLabel}</span>
                        <i class="fas fa-chevron-right mr-chevron"></i>
                      </div>
                    `;
                }).join('');

                list.querySelectorAll('.mr-txntype-row').forEach(row => {
                    row.addEventListener('click', () => {
                        this.selectedTxnType = {
                            txnTypeId:   +row.dataset.tid,
                            txnTypeName:  row.dataset.tname
                        };
                        this._loadPanelC(this.selectedTxnType.txnTypeId, this.selectedTxnType.txnTypeName);
                    });
                });
            }

            // ── Panel C ─────────────────────────────────────────

            async _loadPanelC(txnTypeId, txnTypeName) {
                document.getElementById('mr-panel-c-title').textContent = txnTypeName;
                const body = document.getElementById('mr-mappings-body');
                body.innerHTML = '<div class="text-muted p-4 text-center">Loading…</div>';
                this._slideToPanel('c');

                const mappings = await this.DB.getMappingsForTxnType(txnTypeId) || [];
                this.currentMappings = mappings;
                this._renderMappings(mappings, txnTypeId, txnTypeName);
            }

            _renderMappings(mappings, txnTypeId, txnTypeName) {
                const body = document.getElementById('mr-mappings-body');
                if (!mappings.length) {
                    body.innerHTML = `
                      <div class="mr-empty">
                        <i class="fas fa-link-slash"></i>
                        <p>No journal rules for <strong>${txnTypeName}</strong>.<br>
                        Tap <strong>+</strong> to add the first mapping.</p>
                      </div>`;
                    return;
                }
                body.innerHTML = mappings.map(m => `
                  <div class="mr-mapping-card">
                    <div class="mr-mapping-subtype">
                      <i class="fas fa-tag me-1"></i>
                      ${m.subType ? m.subType : '<em>Unconditional (any)</em>'}
                    </div>
                    <div class="mr-mapping-entry">
                      <span class="mr-dr">Dr</span> ${m.debitCode} — ${m.debitName}
                    </div>
                    <div class="mr-mapping-entry">
                      <span class="mr-cr">Cr</span> ${m.creditCode} — ${m.creditName}
                    </div>
                    <span class="mr-seq-badge"><i class="fas fa-sort-numeric-up me-1"></i>Seq ${m.sequence}</span>
                    <div class="mr-mapping-actions">
                      <button class="btn btn-outline-primary" data-edit-id="${m.id}">
                        <i class="fas fa-pen me-1"></i>Edit
                      </button>
                      <button class="btn btn-outline-danger" data-del-id="${m.id}">
                        <i class="fas fa-trash-alt me-1"></i>Delete
                      </button>
                    </div>
                  </div>
                `).join('');

                body.querySelectorAll('[data-edit-id]').forEach(btn => {
                    btn.addEventListener('click', () => {
                        const mapping = this.currentMappings.find(x => x.id === +btn.dataset.editId);
                        if (mapping) this._openSheet(mapping);
                    });
                });
                body.querySelectorAll('[data-del-id]').forEach(btn => {
                    btn.addEventListener('click', () => this._deleteMapping(+btn.dataset.delId));
                });
            }

            // ── Bottom sheet ─────────────────────────────────────

            _populateAccountSelects() {
                const opts = this.accounts.map(a =>
                    `<option value="${a.id}">${a.code} — ${a.name}</option>`
                ).join('');
                document.getElementById('mr-form-debit').innerHTML  = opts;
                document.getElementById('mr-form-credit').innerHTML = opts;
            }

            _openSheet(mapping) {
                const title = document.getElementById('mr-sheet-title');
                if (mapping) {
                    title.textContent = 'Edit Mapping';
                    document.getElementById('mr-form-id').value       = mapping.id;
                    document.getElementById('mr-form-txntype-id').value = mapping.transactionTypeId;
                    document.getElementById('mr-form-subtype').value  = mapping.subType || '';
                    document.getElementById('mr-form-debit').value    = mapping.debitAccountId;
                    document.getElementById('mr-form-credit').value   = mapping.creditAccountId;
                    document.getElementById('mr-form-seq').value      = mapping.sequence;
                } else {
                    title.textContent = 'Add Mapping';
                    document.getElementById('mr-form-id').value       = '';
                    document.getElementById('mr-form-txntype-id').value = this.selectedTxnType?.txnTypeId || '';
                    document.getElementById('mr-form-subtype').value  = '';
                    document.getElementById('mr-form-debit').value    = this.accounts[0]?.id || '';
                    document.getElementById('mr-form-credit').value   = this.accounts[0]?.id || '';
                    document.getElementById('mr-form-seq').value      = '1';
                }
                document.getElementById('mr-sheet').classList.add('open');
                document.getElementById('mr-overlay').classList.add('active');
            }

            _closeSheet() {
                document.getElementById('mr-sheet').classList.remove('open');
                document.getElementById('mr-overlay').classList.remove('active');
            }

            async _saveMapping() {
                const id      = document.getElementById('mr-form-id').value;
                const payload = {
                    transactionTypeId: +document.getElementById('mr-form-txntype-id').value,
                    subType:           document.getElementById('mr-form-subtype').value.trim() || null,
                    debitAccountId:    +document.getElementById('mr-form-debit').value,
                    creditAccountId:   +document.getElementById('mr-form-credit').value,
                    sequence:          +document.getElementById('mr-form-seq').value || 1,
                };
                if (id) payload.id = +id;

                if (!payload.transactionTypeId || !payload.debitAccountId || !payload.creditAccountId) {
                    alert('Debit and Credit accounts are required.');
                    return;
                }

                const btn = document.getElementById('mr-form-save');
                btn.disabled = true;

                const res = await this.DB.saveMapping(payload);
                btn.disabled = false;

                if (res && !res.error) {
                    this._closeSheet();
                    // Refresh Panel C and Panel A
                    await this._loadPanelC(this.selectedTxnType.txnTypeId, this.selectedTxnType.txnTypeName);
                    await this._loadPanelA();
                } else {
                    alert('Save failed: ' + (res?.error || 'Unknown error'));
                }
            }

            async _deleteMapping(id) {
                if (!confirm('Delete this journal rule?')) return;
                await this.DB.deleteMapping(id);
                await this._loadPanelC(this.selectedTxnType.txnTypeId, this.selectedTxnType.txnTypeName);
                await this._loadPanelA();
            }
        },

        // ── expose ──────────────────────────────────────────────
        expose() {
            const { DB, MODULE_CONFIG, App } = window.screenMap.modules_registry.script;
            const app = new App(DB, MODULE_CONFIG);
            window.init_modules_registry = () => app.init();
        }
    }
};
