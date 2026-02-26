// 0) History stack for multi-level “back”
window.navHistory = [];

// 1) Top-level navigate()
function navigate(route, options) {
  // Normalize & strip leading '#'
  console.log('navigating start')
  const normalized = (route || '').toString().replace(/^#/, '');
  console.log('[navigate] ➔ called', { route, normalized, options });

  // 1a) “Go back” if no route
  if (!normalized) {
    console.log('[navigate] ‑- no route ➔ returnToPage()');
    return returnToPage();
  }

  // 1b) Push current hash onto history
  const current = location.hash.replace(/^#/, '');
  window.navHistory.push(current);
  console.log('[navigate] ‑- pushing current onto navHistory', window.navHistory);

  // 1c) Forward nav
  navigateTo(normalized, options);
  console.log('navigating end')

}


// 2) Forward navigation helper
window.navigateTo = function(route, options = {}) {
  console.log('[navigateTo] ➔', { route, options });

  // 2a) Parse options into params
  const params = {};
  if (typeof options === 'string') {
    const qs = options.replace(/^\?/, '');
    for (const [k, v] of new URLSearchParams(qs)) {
      params[k] = v;
    }
  } else {
    Object.assign(params, options);
  }
  console.log('[navigateTo] ‑- parsed params', params);

  // 2b) Auto-inject returnTo
  const current = location.hash.split('?')[0].replace('#', '') || '';
  params.returnTo = params.returnTo || current;
  console.log('[navigateTo] ‑- with returnTo=', params.returnTo);

  // 2c) JSON-stringify returnParams objects
  if (params.returnParams && typeof params.returnParams !== 'string') {
    params.returnParams = JSON.stringify(params.returnParams);
    console.log('[navigateTo] ‑- serialized returnParams=', params.returnParams);
  }

  // 2d) Build final hash
  const qsOut = new URLSearchParams(params).toString();
  const finalHash = `#${route}${qsOut ? `?${qsOut}` : ''}`;
  console.log('[navigateTo] ‑- setting location.hash=', finalHash);
  location.hash = finalHash;
};


// 3) “Go back” logic
function returnToPage(fallbackRoute = 'dashboard') {
  console.log('[returnToPage] ➔ navHistory=', window.navHistory);

  // 3a) If we have history, pop & go there
  if (window.navHistory.length) {
    const last = window.navHistory.pop();
    console.log('[returnToPage] ‑- popping last=', last, 'new navHistory=', window.navHistory);
    location.hash = `#${last}`;
    console.log('[returnToPage] ‑- navigated back to #'+ last);
    return;
  }

  // 3b) Fallback to returnTo URL-param
  console.log('[returnToPage] ‑- history empty, checking returnTo param');
  const [, qs] = location.hash.split('?');
  const params = qs ? Object.fromEntries(new URLSearchParams(qs)) : {};
  console.log('[returnToPage] ‑- parsed url params', params);

  const target = params.returnTo || fallbackRoute;
  console.log('[returnToPage] ‑- target=', target);

  // 3c) Parse any returnParams payload
  let returnParams = {};
  if (params.returnParams) {
    try {
      const parsed = JSON.parse(params.returnParams);
      if (parsed && typeof parsed === 'object') returnParams = parsed;
      console.log('[returnToPage] ‑- parsed returnParams=', returnParams);
    } catch (e) {
      console.warn('[returnToPage] ‑- malformed returnParams JSON, ignoring', params.returnParams);
    }
  }

  // 3d) Build final back-hash
  const backQs = new URLSearchParams(returnParams).toString();
  const finalHash = `#${target}${backQs ? `?${backQs}` : ''}`;
  console.log('[returnToPage] ‑- setting location.hash=', finalHash);
  location.hash = finalHash;
}


// parse location.hash: “?key=val&…”
function parseHashParams() {
  const [, qs] = location.hash.split('?');
  return qs ? Object.fromEntries(new URLSearchParams(qs)) : {};
}

function initInPageLogPanel(namespace) {
  const appRoot     = document.getElementById('app');
  const placeholder = appRoot.querySelector('.ConsoleLog');
  if (!placeholder) return;

  placeholder.innerHTML = '<i class="fas fa-bug text-danger"></i>';
  placeholder.classList.add('btn', 'btn-outline-secondary', 'btn-sm');
  placeholder.style.cursor = 'pointer';

  const logsData = [];
  let logsContainer;

  ['log','info','warn','error'].forEach(level => {
    const original = console[level];
    console[level] = function(...args) {
      original.apply(console, args);
      const msg = args
        .map(a => typeof a === 'object' ? JSON.stringify(a) : String(a))
        .join(' ');
      logsData.push({ level, msg });
      if (logsContainer) appendEntry({ level, msg });
    };
  });

  function appendEntry({ level, msg }) {
    logsContainer.value += `[${level.toUpperCase()}] ${msg}\n`;
    logsContainer.scrollTop = logsContainer.scrollHeight;
  }

  function showLogModal() {
    if (document.getElementById(`modal_${namespace}`)) return;

    // overlay
    const overlay = document.createElement('div');
    overlay.id = `modal_${namespace}`;
    Object.assign(overlay.style, {
      position:       'fixed',
      top:            '0',
      left:           '0',
      width:          '100vw',
      height:         '100vh',
      background:     'rgba(0, 0, 0, 0.5)',
      display:        'flex',
      alignItems:     'center',
      justifyContent: 'center',
      zIndex:         '10000'
    });
    overlay.addEventListener('click', e => {
      if (e.target === overlay) overlay.remove();
    });

    // modal (90% size, centered)
    const modal = document.createElement('div');
    Object.assign(modal.style, {
      background:    '#000',
      color:         '#fff',
      width:         '90vw',
      height:        '90vh',
      borderRadius:  '4px',
      display:       'flex',
      flexDirection: 'column',
      padding:       '12px',
      boxSizing:     'border-box'
    });
    modal.addEventListener('click', e => e.stopPropagation());
    overlay.appendChild(modal);

    // header
    const header = document.createElement('div');
    Object.assign(header.style, {
      display:        'flex',
      justifyContent: 'flex-end',
      gap:            '8px',
      marginBottom:   '8px'
    });
    modal.appendChild(header);

    // Copy
    const btnCopy = document.createElement('button');
    btnCopy.className = 'btn btn-outline-light btn-sm';
    btnCopy.innerHTML = '<i class="fas fa-copy"></i> Copy';
    btnCopy.addEventListener('click', async () => {
      try {
        await navigator.clipboard.writeText(logsContainer.value);
        btnCopy.textContent = 'Copied!';
        setTimeout(() => btnCopy.innerHTML = '<i class="fas fa-copy"></i> Copy', 1500);
      } catch (err) {
        console.error('Copy failed', err);
      }
    });
    header.appendChild(btnCopy);

    // Clear
    const btnClear = document.createElement('button');
    btnClear.className = 'btn btn-outline-light btn-sm';
    btnClear.innerHTML = '<i class="fas fa-trash-alt"></i> Clear';
    btnClear.addEventListener('click', () => {
      logsData.length = 0;
      logsContainer.value = '';
    });
    header.appendChild(btnClear);

    // Close
    const btnClose = document.createElement('button');
    btnClose.className = 'btn btn-outline-light btn-sm';
    btnClose.innerHTML = '<i class="fas fa-times"></i> Close';
    btnClose.addEventListener('click', () => overlay.remove());
    header.appendChild(btnClose);

    // editable textarea
    const textarea = document.createElement('textarea');
    Object.assign(textarea.style, {
      flex:        '1',
      width:       '100%',
      border:      'none',
      background:  '#000',
      color:       '#fff',
      padding:     '8px',
      fontFamily:  'monospace',
      fontSize:    '0.85em',
      resize:      'none',
      boxSizing:   'border-box'
    });
    textarea.spellcheck = false;
    textarea.value      = '';
    modal.appendChild(textarea);
    logsContainer = textarea;

    logsData.forEach(appendEntry);
    document.body.appendChild(overlay);
  }

  placeholder.addEventListener('click', showLogModal);
}