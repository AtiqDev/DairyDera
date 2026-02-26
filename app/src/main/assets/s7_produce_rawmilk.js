window.screenMap.produce_rawmilk = {
    template: 'produce_rawmilk.html',
    script: {
      ProduceRawMilkApp: class {
        constructor() {
          this.products = [];
          this.uoms = [];
        }

        async init(params = {}) {
          console.log('[ProduceRawMilk] init called', params);

          // title
          document.getElementById('productionFormTitle').textContent = 'Milk Production';

          // reset button
          document.getElementById('resetProductionForm').onclick = () => this.init(params);

          // lookups
          try {
            this.products = JSON.parse(await nativeApi.call('getAllProducts'));
            this.uoms = JSON.parse(await nativeApi.call('getAllUnits'));
          } catch (err) {
            console.error('Error loading lookups', err);
            this.products = [];
            this.uoms = [];
          }

          const prodSel = document.getElementById('prod-product');
          const uomSel = document.getElementById('prod-uom');

          // fill product dropdown
          prodSel.innerHTML = this.products
            .map(p => `<option value="${p.id}">${p.name}</option>`)
            .join('');

          // fill unit dropdown
          uomSel.innerHTML = this.uoms
            .map(u => `<option value="${u.id}">${u.name}</option>`)
            .join('');

          // defaults
          const today = new Date().toISOString().slice(0, 10);
          document.getElementById('prod-date').value = today;
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
        console.log('[ProduceRawMilk] expose called');
        window.ProduceRawMilkApp = new this.ProduceRawMilkApp();
        window.init_produce_milk = window.ProduceRawMilkApp.init.bind(window.ProduceRawMilkApp);
      }
    }
  };
