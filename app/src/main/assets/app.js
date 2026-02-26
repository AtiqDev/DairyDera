// File: app.js

// Map logical screen IDs to actual template filenames
const screenMap0 = {
  daily_stub:  'daily_stub.html',
  inventory_stub:  'inventory_stub.html',
  customers_stub:  'customers_stub.html',
  suppliers_stub:  'suppliers_stub.html',
  expense_stub:   'expense_stub.html',
  reports_stub:    'reports_stub.html',

  transactions:     'transactions.html',

  walkin:           'walkin.html',
  report_sale_edit:       'report_sale_edit.html',
  purchase_asset:       'purchase_asset.html',
  purchase_report:      'purchase_report.html',
  report_purchase_edit: 'report_purchase_edit.html',

  sync:   'sync.html',
  errors: 'error_logs.html',
  sales_report:      'sales_report.html',
  trail_balance:    'trail_balance.html',
  balance_sheet:    'balance_sheet.html',
  income_statement: 'income_statement.html',
  cash_flow:        'cash_flow.html',
  journal_report:   'journal_report.html'
};

async function loadScreenBundled(screenId, params = {}) {
  console.log(`[Router] Loading screen bundle : ${screenId}`, params);

  const entry = screenMap[screenId] || screenMap.dashboard;
  const tplPath = `templates/${entry.template}`;
  console.log(`[Router] Template path resolved: ${tplPath}`);

  try {
    const resp = await fetch(tplPath);
    if (!resp.ok) throw new Error(`HTTP ${resp.status} fetching ${tplPath}`);

    const html = await resp.text();
    const app = document.getElementById('app');
    app.innerHTML = html;
    console.log(`[Router] Injected template for ${screenId}`);

    initInPageLogPanel(screenId);

    // Call the mapped script object’s expose() if present
    if (entry.script && typeof entry.script.expose === 'function') {
      console.log(`[Router] Exposing script for ${screenId}`);
      entry.script.expose(params);
    } else {
      console.warn(`[Router] No expose() found for ${screenId}`);
    }

    // disable mobile autocomplete
    document.querySelectorAll('input').forEach(i => {
      i.autocomplete   = 'off';
      i.autocorrect    = 'off';
      i.autocapitalize = 'off';
      i.spellcheck     = false;
    });
    console.log(`[Router] Disabled mobile autocomplete for inputs`);

    // Call init_<screenId>() if defined
    const initFn = window[`init_${screenId}`];
    if (typeof initFn === 'function') {
      console.log(`[Router] Calling init_${screenId}()`);
      initFn(params);
    } else {
      console.warn(`[Router] init_${screenId} not found`);
    }

  } catch (err) {
    console.error(`[Router] loadScreenBundled() error for ${screenId}`, err);
    showError(err);
  }
}


// Load + inject a screen, then eval scripts and call its init_ hook
async function loadScreen(screenId, params = {}) {
  const fileName = screenMap0[screenId] || screenMap.dashboard;
  const tplPath  = `templates/${fileName}`;
  const tplPath1  = `templates/${screenId}`;
  console.log(fileName);
  console.log(tplPath);
  try {
    const resp = await fetch(tplPath);
    if (!resp.ok) {
      throw new Error(`HTTP ${resp.status} fetching ${tplPath1}`);
    }

    // insert HTML
    const html = await resp.text();
    const app  = document.getElementById('app');
    app.innerHTML = html;
    console.log('adding logger on the screen')
    initInPageLogPanel(screenId);

    // eval any inline <script> tags in global scope
    app.querySelectorAll('script').forEach(old => {
      const code = old.textContent;
      (0, eval)(code);
    });

    // disable mobile autocomplete
    document.querySelectorAll('input').forEach(i => {
      i.autocomplete   = 'off';
      i.autocorrect    = 'off';
      i.autocapitalize = 'off';
      i.spellcheck     = false;
    });

    // call init_<screenId>() if it exists
    const initFn = window[`init_${screenId}`];
    if (typeof initFn === 'function') {
      initFn(params);
    }

  } catch (err) {
      console.error('loadScreen()', err);
    showError(err)
  }
}

const bundledScreens = {
    'dashboard': true,
    'daily_stub': true,
    'suppliers':true,
    'purchase': true,
    'sale': true,
    'supplier_form': true,
    'stock_consumption':true,
    'customers':true,
    'customer_form':true,
    'customer_invoice':true,
    'invoices':true,
    'invoice':true,
    'receive_payment':true,
    'mix_milk':true,
    'produce_milk':true,
    'produce_rawmilk':true,
    'uoms':true,
    'products':true,
    'stock':true,
    'fuel_expense':true,
    'worker_expense':true,
    'account':true,
    'account_types':true,
    'query':true
  };
// Router: when hash changes, pull the right template
function router() {
  const { path, params } = parseHash();
// Normalize path (remove leading/trailing slashes, lowercase if you want consistency)
  const cleanPath = path.toLowerCase().replace(/^\/+|\/+$/g, '') || 'home'; // fallback to 'home' if empty

if (bundledScreens[cleanPath]) {
    loadScreenBundled(cleanPath, params);
  } else {
    loadScreen(cleanPath, params);
  }
}

// boot
// 1) Catch any uncaught JS errors and alert them
window.onerror = function(message, source, lineno, colno, error) {
  // build a single formatted error string
  const errMsg =
    'POP Error: '  + message +
    '\nSource: '   + source  +
    '\nLine: '     + lineno + ':' + colno +
    '\nStack: '    + (error && error.stack || 'n/a');

  // 1) log to console
  console.log(errMsg);

  // 2) show alert
  alert(errMsg);

  // let the browser run its default handler too
  return false;
};

window.addEventListener('hashchange', router);
window.addEventListener('DOMContentLoaded', () => {
   router();
});
