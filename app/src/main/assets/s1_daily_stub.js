// s1_daily_stub.js
window.screenMap.daily_stub = {
    template: 'daily_stub.html',
    script: {
      DailyStub: {
        tbody: null,

        async init(params) {
          console.log('[DailyStub] init called');
          this.tbody = document.getElementById('stockTableBody');
          await this.loadStock();
        },

        async loadStock() {
          try {
            this.tbody.innerHTML = '<tr><td colspan="3" class="text-center text-muted">Loading...</td></tr>';
            
            const raw = await nativeApi.call('getStockSummary');
            console.log('[DailyStub] raw data received:', raw);
            
            // Handle both string and object responses
            const stockList = typeof raw === 'string' ? JSON.parse(raw) : raw;

            if (!Array.isArray(stockList) || !stockList.length) {
              this.tbody.innerHTML = `
                <tr><td colspan="3" class="text-center text-muted">No stock found</td></tr>`;
              return;
            }

            this.tbody.innerHTML = stockList.map(stock => `
              <tr>
                <td>${stock.productName}</td>
                <td class="text-end">${parseFloat(stock.quantity).toFixed(2)}</td>
                <td>${stock.unitName || ''}</td>
              </tr>
            `).join('');
          }
          catch (err) {
            console.error('[DailyStub] Error loading stock:', err);
            this.tbody.innerHTML = `
              <tr><td colspan="3" class="text-center text-danger">Error loading stock: ${err.message || err}</td></tr>`;
          }
        }
      },

      // --- Expose hook for router ---
      expose() {
        console.log("[DailyStub] expose called");
        window.init_daily_stub = this.DailyStub.init.bind(this.DailyStub);
      }
    }
};

