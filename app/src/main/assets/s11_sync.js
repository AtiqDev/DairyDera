window.screenMap.sync = {
    template: 'sync.html',
    script: {
        expose(params) {
            async function init_sync(params) {
                let sett = null;
                try {
                    const raw = await nativeApi.call('getSyncSettings');
                    sett = raw ? JSON.parse(raw) : null;
                } catch (e) {
                    console.warn('Failed to parse sync settings:', e);
                    sett = null;
                }

                document.getElementById('start').value = sett && sett.StartDate ? sett.StartDate : '';
                document.getElementById('end').value   = sett && sett.EndDate   ? sett.EndDate   : '';

                document.getElementById('syncBtn').onclick = async () => {
                    const sd = document.getElementById('start').value, ed = document.getElementById('end').value;
                    const raw_data = await nativeApi.call('querySalesByDate', { start: sd, end: ed });
                    const data = JSON.parse(raw_data);
                    console.log('Sync payload:', data);

                    const payload = {
                        id: sett && sett.id ? sett.id : 0,
                        StartDate: sd,
                        EndDate: ed
                    };

                    nativeApi.post('saveSyncSettings', payload);
                    document.getElementById('status').textContent = 'Status: Success';
                };
            }

            window.init_sync = init_sync;
        }
    }
};
