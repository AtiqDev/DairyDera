window.screenMap.stock = {
    template: 'stock.html',
    script: {
      StockApp: class {
        constructor() {
          this.selProd   = document.getElementById('product-select');
          this.inpQty    = document.getElementById('quantity');
          this.selUnit   = document.getElementById('unit-select');
          this.selType   = document.getElementById('type-select');
          this.btnNew    = document.getElementById('btnStockNew');
          this.btnSave   = document.getElementById('btnStockSave');
          this.btnClear  = document.getElementById('btnStockClear');
          this.btnRecal  = document.getElementById('btnStockRecal');
          this.tblBody   = document.getElementById('stock-history').querySelector('tbody');
          this.products = [];
          this.units = [];
        }

        init(params = {}) {
          this.clearForm();
          this.loadOptions();
          this.renderHistory();
          this.btnNew.onclick = () => this.clearForm();
          this.btnClear.onclick = () => this.clearForm();
          this.btnRecal.onclick = () => this.recalibrate();
          this.selProd.onchange = () => {
            if (this.selProd.value) this.enable(this.inpQty, this.selUnit);
            else this.disable(this.inpQty, this.selUnit);
            this.validateForm();
          };
          this.inpQty.oninput = () => this.validateForm();
          this.selUnit.onchange = () => this.validateForm();
          this.btnSave.onclick = () => this.saveTransaction();
        }

        disable(...els) { els.forEach(el => el.disabled = true); }
        enable(...els) { els.forEach(el => el.disabled = false); }

        async loadOptions() {
          try {
            this.products = JSON.parse(await nativeApi.call('getAllProducts'));
            this.units    = JSON.parse(await nativeApi.call('getAllUnits'));
            this.selProd.innerHTML = '<option value="">Select product</option>' + this.products.map(p => `<option value="${p.id}">${p.name}</option>`).join('');
            this.selUnit.innerHTML = '<option value="">Select unit</option>' + this.units.map(u => `<option value="${u.id}">${u.name}</option>`).join('');
          } catch (e) {
            console.error('[Stock] loadOptions error', e);
          }
        }

        async renderHistory() {
          try {
            const history = JSON.parse(await nativeApi.call('getStockSummary'));
            this.tblBody.innerHTML = history.map(h => `<tr><td>${h.productName}</td><td>${h.quantity}</td><td>${h.unitName}</td><td>${h.lastUpdated}</td></tr>`).join('');
          } catch (e) {
            this.tblBody.innerHTML = '<tr><td colspan="4">Error loading history</td></tr>';
          }
        }

        clearForm() {
          this.selProd.value = ''; this.inpQty.value = ''; this.selUnit.value = ''; this.selType.value = 'in';
          this.disable(this.inpQty, this.selUnit, this.btnSave);
        }

        validateForm() {
          this.btnSave.disabled = !(this.selProd.value && this.inpQty.value && this.selUnit.value);
        }

        async recalibrate() {
          try {
            await nativeApi.post('recalibrateStock');
            alert('Recalibrated successfully');
            this.clearForm();
            this.renderHistory();
          } catch (e) {
            alert('Failed to recalibrate.');
          }
        }

        async saveTransaction() {
          const pid = +this.selProd.value;
          const qty = parseFloat(this.inpQty.value);
          const uid = +this.selUnit.value;
          const type = this.selType.value;
          if (!pid || !qty || !uid) return alert('Select product, unit and enter qty.');

          try {
            const baseObj = JSON.parse(await nativeApi.call('getProductBaseUnit', {productId: pid.toString()}))[0];
            const baseUom = baseObj.baseUomId;
            const factor  = await window.getConversionFactor(uid, baseUom);
            const baseQty = qty * factor;

            await nativeApi.post('saveTransaction', {
              productId: pid,
              transactionType: type,
              quantity: baseQty,
              unitId: baseUom
            });
            this.clearForm();
            this.renderHistory();
          } catch (e) {
            alert('Failed to save transaction.');
          }
        }
      },
      expose() {
        const app = new this.StockApp();
        window.init_stock = app.init.bind(app);
      }
    }
  };

