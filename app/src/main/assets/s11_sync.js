window.screenMap.sync = {
    template: 'sync.html',
    script: {
        expose(params) {
            async function init_sync(params) {
                const urlEl    = document.getElementById('sync-server-url');
                const deviceEl = document.getElementById('sync-device-id');
                const keyEl    = document.getElementById('sync-api-key');
                const saveBtn  = document.getElementById('sync-save-btn');
                const saveMsg  = document.getElementById('sync-save-status');
                const lastTime = document.getElementById('sync-last-time');
                const syncBtn  = document.getElementById('sync-now-btn');
                const result   = document.getElementById('sync-result');

                // Load saved settings
                try {
                    const raw = await nativeApi.call('getSyncMeta');
                    const meta = raw ? JSON.parse(raw) : {};
                    urlEl.value    = meta.serverUrl    || '';
                    deviceEl.value = meta.deviceId     || '';
                    keyEl.value    = meta.apiKey       || '';
                    if (meta.lastSyncTime) {
                        const d = new Date(parseInt(meta.lastSyncTime));
                        lastTime.textContent = 'Last sync: ' + d.toLocaleString();
                    }
                } catch (e) {
                    console.warn('getSyncMeta failed:', e);
                }

                // Save settings
                saveBtn.onclick = async () => {
                    saveMsg.textContent = '';
                    const payload = {
                        serverUrl: urlEl.value.trim(),
                        deviceId:  deviceEl.value.trim(),
                        apiKey:    keyEl.value.trim()
                    };
                    try {
                        await nativeApi.call('saveSyncMeta', payload);
                        saveMsg.style.color = '#4caf50';
                        saveMsg.textContent = 'Settings saved.';
                    } catch (e) {
                        saveMsg.style.color = '#f44336';
                        saveMsg.textContent = 'Save failed: ' + e.message;
                    }
                };

                // Sync now
                syncBtn.onclick = async () => {
                    syncBtn.disabled = true;
                    syncBtn.textContent = 'Syncing…';
                    result.style.color = '#333';
                    result.textContent = '';
                    try {
                        const raw = await nativeApi.call('runSync');
                        const res = raw ? JSON.parse(raw) : {};
                        if (res.error) {
                            result.style.color = '#f44336';
                            result.textContent = '✗ ' + res.error;
                        } else {
                            result.style.color = '#388e3c';
                            result.textContent = '✓ ' + (res.message || 'Done') + ' (' + (res.synced || 0) + ' rows)';
                            // Refresh last sync time
                            const raw2 = await nativeApi.call('getSyncMeta');
                            const meta2 = raw2 ? JSON.parse(raw2) : {};
                            if (meta2.lastSyncTime) {
                                const d = new Date(parseInt(meta2.lastSyncTime));
                                lastTime.textContent = 'Last sync: ' + d.toLocaleString();
                            }
                        }
                    } catch (e) {
                        result.style.color = '#f44336';
                        result.textContent = '✗ ' + e.message;
                    } finally {
                        syncBtn.disabled = false;
                        syncBtn.textContent = 'Sync Now';
                    }
                };
            }

            window.init_sync = init_sync;
        }
    }
};
