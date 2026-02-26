// s2_stock_consumption.js
Object.assign(window.screenMap, {
  stock_consumption: {
    template: 'stock_consumption.html',
    script: {
      DataBridge: {
        async safeCall(fnName, defaultVal = [], payload = {}) {
          try {
            const raw = await nativeApi.call(fnName, payload);
            return raw ? JSON.parse(raw) : defaultVal;
          } catch (err) {
            console.error(`[Bridge] ${fnName} failed`, err);
          }
          return defaultVal;
        },
        async getAllUnits() { return this.safeCall('getAllUnits'); },
        async getStockSummary() { return this.safeCall('getStockSummary'); },
        async getProductBaseUnit(pid) { return this.safeCall('getProductBaseUnit', [], { productId: pid }); },
        async saveConsumption(payload) { return nativeApi.post('saveConsumption', payload); }
      },

      StockConsumptionApp: class {
        constructor() {
          this.tblBody = null;
          this.selQty = null;
          this.selUnit = null;
          this.notesInput = null;
          this.btnClear = null;
          this.btnConsume = null;
          this.displayProd = null;
          this.pidInput = null;
          this.selectedUnitPrice = 0;
          this.units = [];
        }

        async init(params = {}) {
          this.tblBody = document.querySelector('#stock-table tbody');
          this.selQty = document.getElementById('quantity');
          this.selUnit = document.getElementById('unit-select');
          this.notesInput = document.getElementById('notes');
          this.btnClear = document.getElementById('btnStockClear');
          this.btnConsume = document.getElementById('btnStockConsume');
          this.displayProd = document.getElementById('selected-product');
          this.pidInput = document.getElementById('selected-product-id');

          this.btnClear.onclick = () => this.clearSelection();
          this.btnConsume.onclick = () => this.consume();

          this.clearSelection();
          await this.loadUnits();
          await this.renderStockTable();
        }

        async loadUnits() {
          const DB = window.screenMap.stock_consumption.script.DataBridge;
          this.units = await DB.getAllUnits();
          this.selUnit.innerHTML = '<option value="">Select unit</option>' + this.units.map(u => `<option value="${u.id}">${u.name}</option>`).join('');
        }

        async renderStockTable() {
          const DB = window.screenMap.stock_consumption.script.DataBridge;
          const stocks = (await DB.getStockSummary()).filter(s => s.quantity > 0);
          this.tblBody.innerHTML = stocks.map(s => `
            <tr data-id="${s.productId}" data-unit-price="${parseFloat(s.unitPrice || 0).toFixed(2)}">
              <td>${s.productName}</td>
              <td>${s.quantity}</td>
              <td>${s.unitName}</td>
              <td>${parseFloat(s.unitPrice || 0).toFixed(2)}</td>
            </tr>`).join('');
          this.tblBody.querySelectorAll('tr').forEach(row => row.onclick = () => this.selectProduct(row));
        }

        clearSelection() {
          this.tblBody.querySelectorAll('tr.table-primary').forEach(r => r.classList.remove('table-primary'));
          this.pidInput.value = ''; this.displayProd.textContent = ''; this.selQty.value = ''; this.notesInput.value = '';
          this.selQty.disabled = this.selUnit.disabled = this.notesInput.disabled = this.btnConsume.disabled = true;
        }

        async selectProduct(row) {
          this.clearSelection();
          row.classList.add('table-primary');
          const pid = +row.dataset.id;
          const price = parseFloat(row.dataset.unitPrice) || 0;
          this.pidInput.value = pid;
          this.displayProd.textContent = row.cells[0].textContent;
          this.selectedUnitPrice = price;

          const DB = window.screenMap.stock_consumption.script.DataBridge;
          const baseObj = (await DB.getProductBaseUnit(pid.toString()))[0];
          this.selUnit.value = baseObj.baseUomId;

          this.selQty.disabled = this.selUnit.disabled = this.notesInput.disabled = this.btnConsume.disabled = false;
        }

        async consume() {
          const pid = +this.pidInput.value;
          const qty = parseFloat(this.selQty.value);
          const unitId = this.selUnit.value;
          const notes = this.notesInput.value.trim();

          if (!pid || isNaN(qty) || qty <= 0 || !unitId) return alert('Select product, unit and enter quantity > 0.');

          const DB = window.screenMap.stock_consumption.script.DataBridge;
          const baseObj = (await DB.getProductBaseUnit(pid.toString()))[0];
          const factor = await window.getConversionFactor(unitId, baseObj.baseUomId);
          const baseQty = qty * factor;

          await DB.saveConsumption({
            productId: pid,
            transactionType: 'out',
            quantity: baseQty,
            unitId: baseObj.baseUomId,
            unitPrice: this.selectedUnitPrice,
            notes: notes
          });
          setTimeout(() => window.navigate(), 300);
        }
      },
      expose() {
        window.StockConsumptionApp = new this.StockConsumptionApp();
        window.init_stock_consumption = window.StockConsumptionApp.init.bind(window.StockConsumptionApp);
      }
    }
  }
});

