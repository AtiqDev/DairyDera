window.screenMap['livestock_stub'] = {
  template: 'livestock_stub.html',
  script: {
    expose() {
      window.init_livestock_stub = (params = {}) => {
        nativeApi.call('getHerdSummary', {}).then(raw => {
          const s = raw ? JSON.parse(raw) : {};
          const el = document.getElementById('ls-summary');
          if (el) {
            el.innerHTML = `
              <div class="d-flex gap-3 flex-wrap">
                <span class="badge bg-success">${s.active || 0} Active</span>
                <span class="badge bg-secondary">${s.dry || 0} Dry</span>
                <span class="badge bg-info text-dark">${s.pregnant || 0} Pregnant</span>
                <span class="badge bg-danger">${s.sick || 0} Sick</span>
                <span class="badge bg-warning text-dark">${s.calves || 0} Calves</span>
              </div>`;
          }
        }).catch(err => console.error('getHerdSummary', err));
      };
    }
  }
};
