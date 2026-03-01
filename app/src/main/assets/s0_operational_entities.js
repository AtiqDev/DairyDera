// File: s0_operational_entities.js
// Screen: operational_entities
// Admin screen for managing operational entity → module assignments.
// 3-panel flow: Modules → Entities in module → Entity detail

window.screenMap = window.screenMap || {};
window.screenMap['operational_entities'] = {
    template: 'templates/operational_entities.html',
    script: {
        expose: function (params) {
            window.init_operational_entities(params || {});
        }
    }
};

window.init_operational_entities = function (params) {

    // ── module colours (matches modules_registry palette) ────────────────────
    const MODULE_COLORS = {
        'Procurement': '#6f42c1',
        'Sales':       '#0d6efd',
        'Production':  '#198754',
        'Expenses':    '#fd7e14'
    };
    function moduleColor(name) { return MODULE_COLORS[name] || '#6c757d'; }

    // ── panel nav helpers ─────────────────────────────────────────────────────
    function showPanel(id) {
        ['oe-panel-a','oe-panel-b','oe-panel-c'].forEach(pid => {
            const el = document.getElementById(pid);
            if (el) el.classList.toggle('slide-right', pid !== id);
        });
    }

    // ── overlay / sheet ───────────────────────────────────────────────────────
    const overlay = document.getElementById('oe-overlay');
    const sheet   = document.getElementById('oe-sheet');
    function openSheet() { sheet.classList.add('open'); overlay.classList.add('active'); }
    function closeSheet(){ sheet.classList.remove('open'); overlay.classList.remove('active'); }
    overlay.addEventListener('click', closeSheet);

    // ── state ─────────────────────────────────────────────────────────────────
    let selectedModuleId   = null;
    let selectedModuleName = null;
    let selectedEntityId   = null;
    let allModules         = [];

    // ── PANEL A: load modules ─────────────────────────────────────────────────
    function loadPanelA() {
        Android.callDb('getModulesWithEntityCount', null, function (raw) {
            const modules = JSON.parse(raw);
            allModules = modules;
            const grid = document.getElementById('oe-module-grid');
            grid.innerHTML = modules.map(m => {
                const color = moduleColor(m.name);
                return `
                <div class="oe-module-card" onclick="window._oe_openModule(${m.id},'${m.name}')">
                  <div class="oe-module-icon" style="background:${color}">
                    <i class="fas fa-layer-group"></i>
                  </div>
                  <div class="oe-module-name">${m.name}</div>
                  <div class="oe-entity-count">${m.entityCount} entit${m.entityCount === 1 ? 'y' : 'ies'}</div>
                </div>`;
            }).join('');

            loadUnassigned();
        });
    }

    function loadUnassigned() {
        Android.callDb('getUnassignedEntities', null, function (raw) {
            const entities = JSON.parse(raw);
            const section = document.getElementById('oe-unassigned-section');
            const list    = document.getElementById('oe-unassigned-list');
            if (!entities.length) { section.style.display = 'none'; return; }
            section.style.display = '';
            list.innerHTML = entities.map(e => entityRow(e, null, true)).join('');
        });
    }

    // ── PANEL B: entities for a module ────────────────────────────────────────
    window._oe_openModule = function (moduleId, moduleName) {
        selectedModuleId   = moduleId;
        selectedModuleName = moduleName;
        document.getElementById('oe-panel-b-title').textContent = moduleName;
        showPanel('oe-panel-b');
        loadPanelB();
    };

    document.getElementById('oe-back-b').addEventListener('click', function () {
        showPanel('oe-panel-a');
        loadPanelA();
    });

    function loadPanelB() {
        Android.callDb('getEntitiesByModule', { moduleId: selectedModuleId }, function (raw) {
            const entities = JSON.parse(raw);
            const list = document.getElementById('oe-entity-list');
            if (!entities.length) {
                list.innerHTML = `<div class="oe-empty"><i class="fas fa-inbox"></i><p>No entities assigned.</p></div>`;
                return;
            }
            list.innerHTML = entities.map(e => entityRow(e, selectedModuleId, false)).join('');
        });
    }

    // ── PANEL C: entity detail ────────────────────────────────────────────────
    window._oe_openEntity = function (entityId) {
        selectedEntityId = entityId;
        showPanel('oe-panel-c');
        loadPanelC();
    };

    document.getElementById('oe-back-c').addEventListener('click', function () {
        showPanel('oe-panel-b');
    });

    function loadPanelC() {
        Android.callDb('getEntityDetail', { entityId: selectedEntityId }, function (raw) {
            const e = JSON.parse(raw);
            if (e.error) { alert(e.error); return; }
            document.getElementById('oe-panel-c-title').textContent = e.EntityName;
            document.getElementById('oe-detail-body').innerHTML = `
              <div class="oe-detail-card">
                <div class="oe-detail-label">Entity Name</div>
                <div class="oe-detail-value">${e.EntityName}</div>
              </div>
              <div class="oe-detail-card">
                <div class="oe-detail-label">Type</div>
                <div class="oe-detail-value">${e.EntityType}</div>
              </div>
              <div class="oe-detail-card">
                <div class="oe-detail-label">Source Table</div>
                <div class="oe-detail-value">${e.TableName}</div>
              </div>
              <div class="oe-detail-card">
                <div class="oe-detail-label">Amount Column</div>
                <div class="oe-detail-value">${e.MonetaryColumnName}</div>
              </div>
              <div class="oe-detail-card">
                <div class="oe-detail-label">Assigned Module</div>
                <div class="oe-detail-value">${e.moduleName || '<span style="color:#aaa">Unassigned</span>'}</div>
              </div>
              <button class="btn btn-outline-primary w-100 mt-2" onclick="window._oe_showAssignSheet(${e.id})">
                <i class="fas fa-exchange-alt me-1"></i> Assign / Move to Module
              </button>
              ${e.moduleId ? `
              <button class="btn btn-outline-danger w-100 mt-2" onclick="window._oe_removeFromModule(${e.id})">
                <i class="fas fa-unlink me-1"></i> Remove from Module
              </button>` : ''}
            `;
        });
    }

    // ── assign sheet ──────────────────────────────────────────────────────────
    window._oe_showAssignSheet = function (entityId) {
        selectedEntityId = entityId;
        const body = document.getElementById('oe-sheet-body');
        body.innerHTML = allModules.map(m => {
            const color = moduleColor(m.name);
            return `
            <div class="oe-module-option" onclick="window._oe_assignTo(${entityId},${m.id})">
              <div class="oe-module-dot" style="background:${color}"></div>
              <span>${m.name}</span>
            </div>`;
        }).join('');
        openSheet();
    };

    window._oe_assignTo = function (entityId, moduleId) {
        closeSheet();
        Android.callDb('assignEntityToModule', { entityId, moduleId }, function (raw) {
            const res = JSON.parse(raw);
            if (res.error) { alert(res.error); return; }
            loadPanelA();
            loadPanelC();
        });
    };

    window._oe_removeFromModule = function (entityId) {
        if (!confirm('Remove this entity from its module?')) return;
        Android.callDb('removeEntityFromModule', { entityId }, function (raw) {
            const res = JSON.parse(raw);
            if (res.error) { alert(res.error); return; }
            loadPanelA();
            showPanel('oe-panel-b');
            loadPanelB();
        });
    };

    // ── entity row HTML helper ────────────────────────────────────────────────
    function entityRow(e, moduleId, showAssignBtn) {
        const removeBtn = moduleId
            ? `<button class="oe-remove-btn" onclick="event.stopPropagation();window._oe_removeFromModule(${e.id})">
                 <i class="fas fa-unlink"></i>
               </button>`
            : '';
        const assignBtn = showAssignBtn
            ? `<button class="oe-remove-btn" style="background:#e8f4fd;color:#0d6efd"
                 onclick="event.stopPropagation();window._oe_showAssignSheet(${e.id})">
                 <i class="fas fa-plus"></i>
               </button>`
            : '';
        return `
        <div class="oe-entity-row" onclick="window._oe_openEntity(${e.id})">
          <div class="oe-entity-info">
            <div class="oe-entity-name">${e.EntityName}</div>
            <div class="oe-entity-meta">${e.TableName}.${e.MonetaryColumnName}</div>
          </div>
          <span class="oe-entity-type-badge">${e.EntityType}</span>
          ${removeBtn}${assignBtn}
          <i class="fas fa-chevron-right oe-chevron"></i>
        </div>`;
    }

    // ── init ──────────────────────────────────────────────────────────────────
    showPanel('oe-panel-a');
    loadPanelA();
};
