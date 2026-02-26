// Add tiny animation once
if (!document.querySelector('#errorToastStyle')) {
  const s = document.createElement('style');
  s.id = 'errorToastStyle';
  s.textContent = '@keyframes toast{from{opacity:0;transform:translateX(-50%) translateY(20px)}to{opacity:1;transform:translateX(-50%)}}';
  document.head.appendChild(s);
}

window.handleSaveStatus = (isSuccessful) => {
    window.PurchaseFormApp.saveBtn.disabled = false;
    if (isSuccessful === true) {
        console.log(" save Status true");
        window.navigate();
    } else {
        console.error("save failed");
        alert("Error saving Purchase Order. Please check data and try again.");
    }
};

// Get conversion factor (fromUnit to toUnit)
async function getConversionFactor(fromUnit, toUnit) {
  if (fromUnit === toUnit) return 1;
  let convRaw = await nativeApi.call('getConversion', { fromUnit, toUnit });
  let conv = JSON.parse(convRaw);
  if (conv.length) return conv[0].ConversionFactor;
  convRaw = await nativeApi.call('getConversion', { fromUnit: toUnit, toUnit: fromUnit });
  conv = JSON.parse(convRaw);
  return conv.length ? 1 / conv[0].ConversionFactor : 1;
}

// parse location.hash → { path, params }
function parseHash() {
  const [p = 'dashboard', qs = ''] = location.hash.slice(1).split('?');
  return {
    path:   p || 'dashboard',
    params: Object.fromEntries(new URLSearchParams(qs))
  };
}

function showError(err, screenId = 'Screen') {
  // Prevent multiple overlays
  document.querySelectorAll('#errorOverlay').forEach(el => el.remove());

  const errorText = `Error Loading ${screenId}\n\n` +
    `${err.stack || err.message || String(err)}\n\n` +
    `Time: ${new Date().toLocaleString()}\n` +
    `UA: ${navigator.userAgent.split(' ').slice(0, 4).join(' ')}`;

  // Auto-copy to clipboard silently
  navigator.clipboard?.writeText(errorText).catch(() => {});

  // Create overlay
  const overlay = document.createElement('div');
  overlay.id = 'errorOverlay';
  overlay.style.cssText = `
    position:fixed;inset:0;background:rgba(0,0,0,0.8);
    display:flex;align-items:center;justify-content:center;
    z-index:99999;font-family:sans-serif;
  `;
  overlay.onclick = (e) => e.target === overlay && overlay.remove();

  // Modal container
  const modal = document.createElement('div');
  modal.style.cssText = `
    background:#111;color:#0f0;width:92vw;max-width:500px;height:88vh;
    border:2px solid #0f0;border-radius:12px;display:flex;flex-direction:column;
    box-shadow:0 0 30px rgba(0,255,0,0.4);
  `;
  modal.onclick = e => e.stopPropagation();

  // Header
  const header = document.createElement('div');
  header.style.cssText = 'display:flex;justify-content:space-between;align-itemsured;padding:10px 15px;border-bottom:1px solid #333;';
  header.innerHTML = `<div style="color:#f44;font-weight:bold;">⚠️ Error</div>`;

  const btns = document.createElement('div');
  btns.style.cssText = 'display:flex;gap:10px;';

  // Copy button
  const btnCopy = document.createElement('button');
  btnCopy.textContent = '📋 Copy';
  btnCopy.style.cssText = 'background:transparent;border:1px solid #0f0;color:#0f0;padding:6px 12px;border-radius:6px;cursor:pointer;';
  btnCopy.onclick = () => {
    navigator.clipboard.writeText(textarea.value);
    btnCopy.textContent = '✓ Copied';
    setTimeout(() => btnCopy.textContent = '📋 Copy', 1500);
  };

  // Close button
  const btnClose = document.createElement('button');
  btnClose.textContent = '✕';
  btnClose.style.cssText = 'background:transparent;border:1px solid #f44;color:#f44;padding:6px 12px;border-radius:6px;cursor:pointer;';
  btnClose.onclick = () => overlay.remove();

  btns.append(btnCopy, btnClose);
  header.appendChild(btns);

  // Textarea with error
  const textarea = document.createElement('textarea');
  textarea.value = errorText;
  textarea.readOnly = true;
  textarea.spellcheck = false;
  textarea.style.cssText = `
    flex:1;background:#000;color:#0f0;border:none;outline:none;
    padding:15px;font-family:monospace;font-size:13px;line-height:1.5;
    resize:none;overflow-y:auto;
  `;

  // Build it
  modal.append(header, textarea);
  overlay.appendChild(modal);
  document.getElementById('app').innerHTML = '';   // or document.body.append(overlay) if you prefer
  document.getElementById('app').appendChild(overlay);

  // Tiny toast
  const toast = document.createElement('div');
  toast.textContent = 'Error copied to clipboard';
  toast.style.cssText = `
    position:fixed;bottom:30px;left:50%;transform:translateX(-50%);
    background:#0f0;color:#000;padding:10px 20px;border-radius:50px;
    font-weight:bold;z-index:100000;animation:fade,toast 0.4s;
  `;
  document.body.appendChild(toast);
  setTimeout(() => toast.remove(), 2000);
}

window.screenMap = window.screenMap || {};