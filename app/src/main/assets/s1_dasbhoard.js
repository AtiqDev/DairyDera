// initScreenMapDashboard.js
window.screenMap.dashboard = {
    template: 'dashboard.html',
    script: {
     Dashboard: {
       // --- Helper: "YYYY-MM" → "Mon YYYY"
       formatMonth(ym) {
         const parts = ym.split('-');
         if (parts.length < 2) return ym;
         const year = parts[0];
         const m = parseInt(parts[1], 10);
         if (isNaN(m) || m < 1 || m > 12) return ym;
         const monthNames = [
           'Jan','Feb','Mar','Apr','May','Jun',
           'Jul','Aug','Sep','Oct','Nov','Dec'
         ];
         return `${monthNames[m - 1]} ${year}`;
       },

       // --- Entry point ---
       async init(params) {
         console.log("[Dashboard] init called", params);
         const container = document.getElementById('sales-summary');
         if (!container) {
           console.error("[Dashboard] #sales-summary not found");
           return;
         }
         container.innerHTML = '';

         let sales = [];
         try {
           sales = JSON.parse(await nativeApi.call('getSalesPerMonthToDate'));
           console.log("[Dashboard] Sales data loaded", sales);
         } catch (e) {
           console.error('[Dashboard] Error loading sales:', e);
         }

         if (!sales.length) {
           container.insertAdjacentHTML(
             'beforeend',
             `<div class="col-12 text-muted">No sales data available.</div>`
           );
           console.warn("[Dashboard] No sales data available");
           return;
         }

         const cards = sales.map(s => `
           <div class="col-12 col-sm-6 col-md-4 col-lg-3">
             <div class="bg-white rounded shadow-sm p-3 h-100">
               <h6 class="mb-2">${this.formatMonth(s.month)}</h6>
               <span class="badge bg-primary me-1">Sales: ${s.saleCount}</span>
               <span class="badge bg-success me-1">Qty: ${s.totalQuantity}</span>
               <span class="badge bg-dark">Total: ${s.totalAmount}</span>
             </div>
           </div>
         `).join('');

         container.insertAdjacentHTML('beforeend', cards);
         console.log("[Dashboard] Cards rendered:", sales.length);
       }
     },

     // --- Expose hook for router ---
     expose() {
       console.log("[Dashboard] expose called");
       window.init_dashboard = this.Dashboard.init.bind(this.Dashboard);
     }
    }
};


