window.screenMap.products = {
  template: 'products.html',
  script: {
    ProductsApp: class {
      constructor() {
        this.selectedProdId = null;
        this.selectedProdRow = null;
      }

      async init(params = {}) {
        console.log('[Products] init called', params);
        this.clearProductForm();
        this.loadUnitOptions();
        this.renderProductsTable();

        // If navigated with an id param, enter edit mode
        if (params.id) {
          const id = +params.id;
          const all = JSON.parse(await nativeApi.call('getAllProducts'));
          const prod = all.find(p => p.id === id);
          if (prod) {
            this.highlightRow(id);
            this.enterEditMode(prod);
          } else {
            alert('Product not found.');
            navigate('products');
          }
        }

        // Bind buttons
        document.getElementById('btnProdNew').onclick = () => this.clearProductForm();
        document.getElementById('btnProdSave').onclick = () => this.saveProduct();
        document.getElementById('btnProdDelete').onclick = () => this.deleteProduct();
      }

      async loadUnitOptions() {
        const uoms = JSON.parse(await nativeApi.call('getAllUnits'));
        const sel = document.getElementById('base-unit');
        if (!uoms.length) {
          document.getElementById('no-units-warning').style.display = 'block';
          sel.innerHTML = '<option value="">No units</option>';
          this.disableButtons();
          return;
        }
        document.getElementById('no-units-warning').style.display = 'none';
        sel.innerHTML = '<option value="">Select unit</option>' +
          uoms.map(u => `<option value="${u.id}">${u.name}</option>`).join('');
        this.enable(document.getElementById('btnProdNew'));
      }

      async renderProductsTable() {
        const list = JSON.parse(await nativeApi.call('getAllProducts'));
        const tbody = document.getElementById('products-table').querySelector('tbody');

        tbody.innerHTML = list.map(p => `
            <tr data-id="${p.id}">
              <td>${p.id}</td>
              <td>${p.name}</td>
              <td>${p.unit_name}</td>
            </tr>
          `).join('');

        tbody.onclick = async e => {
          const row = e.target.closest('tr');
          if (!row) return;
          const id = +row.dataset.id;
          if (this.selectedProdRow) this.selectedProdRow.classList.remove('table-primary');
          row.classList.add('table-primary');
          this.selectedProdRow = row;
          this.selectedProdId = id;

          const prod = JSON.parse(await nativeApi.call('getProduct', { id: id.toString() }))[0];
          this.enterEditMode(prod);
        };
      }

      highlightRow(id) {
        const rows = document.querySelectorAll('#products-table tbody tr');
        rows.forEach(r => {
          if (+r.dataset.id === id) {
            r.classList.add('table-primary');
            this.selectedProdRow = r;
            this.selectedProdId = id;
          } else {
            r.classList.remove('table-primary');
          }
        });
      }

      enterEditMode(prod) {
        document.getElementById('prod-id').value = prod.id;
        document.getElementById('prod-name').value = prod.name;
        document.getElementById('prod-desc').value = prod.description || '';
        document.getElementById('base-unit').value = prod.baseUomId;

        document.getElementById('formTitle').textContent = 'Edit Product';
        document.getElementById('formTitleCard').textContent = 'Edit Product';

        this.enable(document.getElementById('btnProdSave'));
        this.enable(document.getElementById('btnProdDelete'));
      }

      clearProductForm() {
        if (this.selectedProdRow) this.selectedProdRow.classList.remove('table-primary');
        this.selectedProdRow = null;
        this.selectedProdId = null;

        document.getElementById('prod-id').value = '';
        document.getElementById('prod-name').value = '';
        document.getElementById('prod-desc').value = '';
        document.getElementById('base-unit').value = '';

        document.getElementById('formTitle').textContent = 'Add Product';
        document.getElementById('formTitleCard').textContent = 'Add Product';

        this.enable(document.getElementById('btnProdNew'));
        this.enable(document.getElementById('btnProdSave'));
        this.disable(document.getElementById('btnProdDelete'));
      }

      disableButtons() {
        this.disable(document.getElementById('btnProdSave'));
        this.disable(document.getElementById('btnProdDelete'));
        this.disable(document.getElementById('btnProdNew'));
      }

      enable(btn) { btn.disabled = false; }
      disable(btn) { btn.disabled = true; }

      async saveProduct() {
        const id = +document.getElementById('prod-id').value || 0;
        const name = document.getElementById('prod-name').value.trim();
        const desc = document.getElementById('prod-desc').value.trim();
        const uom = +document.getElementById('base-unit').value;

        if (!name) return alert('name required.');
        if (!uom) return alert('Select base unit.');

        const payload = {
          id: id,
          product_name: name,
          description: desc,
          base_unit_id: uom
        };

        try {
          await nativeApi.post('saveProduct', payload);
          this.clearProductForm();
          this.loadUnitOptions();
          this.renderProductsTable();
        } catch (e) {
          console.error('[Products] saveProduct error', e);
          alert('Failed to save product.');
        }
      }

      async deleteProduct() {
        if (!this.selectedProdId) return;
        if (!confirm('Delete this product?')) return;

        try {
          await nativeApi.post('deleteProduct', { id: this.selectedProdId.toString() });
          this.clearProductForm();
          this.loadUnitOptions();
          this.renderProductsTable();
        } catch (e) {
          console.error('[Products] deleteProduct error', e);
          alert('Failed to delete product.');
        }
      }
    },

    expose() {
      console.log('[Products] expose called');
      const app = new this.ProductsApp();
      window.ProductsApp = app;
      window.init_products = app.init.bind(app);
    }
  }
};
