window.screenMap.produce_milk = {
  template: 'produce_milk.html',
  script: {
    ProduceMilkApp: class {
      constructor() {
        this.products = [];
        this.uoms = [];
      }

      async init(params = {}) {
        console.log('[ProduceMilk] init called', params);

        const titleEl = document.getElementById('productionFormTitle');
        titleEl.textContent = 'Milk Production';

        document.getElementById('resetProductionForm').onclick = () => this.init(params);

        // lookups
        try {
          this.uoms = JSON.parse(await nativeApi.call('getAllUnits'));
          this.products = JSON.parse(await nativeApi.call('getSellableProducts'));
        } catch (err) {
          console.error('Error loading lookups', err);
          this.uoms = [];
          this.products = [];
        }

        const prodSel = document.getElementById('prod-product');
        const uomSel = document.getElementById('prod-uom');

        // fill product dropdown
        prodSel.innerHTML = this.products
          .map(p => `<option value="${p.id}" data-uomid="${p.unit_id || p.baseUomId}">${p.name}</option>`)
          .join('');

        // fill unit dropdown
        uomSel.innerHTML = this.uoms
          .map(u => `<option value="${u.id}">${u.name}</option>`)
          .join('');

        // sync unit with product
        const syncUnitWithProduct = () => {
          const prod = this.products.find(p => p.id == prodSel.value);
          if (!prod) return;
          const targetId = prod.unit_id || prod.baseUomId;
          uomSel.value = targetId;
        };

        prodSel.addEventListener('change', syncUnitWithProduct);

        // preselect first product + its unit
        if (this.products.length > 0) {
          prodSel.value = this.products[0].id;
          syncUnitWithProduct();
        }

        // default date
        document.getElementById('prod-date').value = new Date().toISOString().slice(0, 10);
        document.getElementById('prod-qty').value = '';
        document.getElementById('prod-notes').value = '';

        // bind save
        document.getElementById('saveProductionForm').onclick = () => this.save();
      }

      async save() {
        const date = document.getElementById('prod-date').value;
        const qty = parseFloat(document.getElementById('prod-qty').value);
        const productId = parseInt(document.getElementById('prod-product').value, 10);
        const uomId = parseInt(document.getElementById('prod-uom').value, 10);
        const notes = document.getElementById('prod-notes').value.trim();

        if (!date || !qty || !productId || !uomId) {
          alert('Please fill in all required fields.');
          return;
        }

        const payload = {
          productId: productId,
          quantity: qty,
          uomId: uomId,
          date: date,
          notes: notes
        };

        try {
          await nativeApi.post('saveMilkProduction', payload);
          navigate();
        } catch (err) {
          console.error('saveMilkProduction error', err);
          alert('Failed to save production.');
        }
      }
    },

    expose() {
      console.log('[ProduceMilk] expose called');
      window.ProduceMilkApp = new this.ProduceMilkApp();
      window.init_produce_milk = window.ProduceMilkApp.init.bind(window.ProduceMilkApp);
    }
  }
};
