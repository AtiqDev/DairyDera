window.screenMap.fuel_expense = {
    template: 'fuel_expense.html',
    script: {
      FuelExpenseApp: class {
        constructor() {
          this.dateEl  = document.getElementById('fuel-date');
          this.amtEl   = document.getElementById('fuel-amt');
          this.notesEl = document.getElementById('fuel-notes');
          this.resetBtn = document.getElementById('resetFuelForm');
          this.saveBtn  = document.getElementById('saveFuelForm');
        }

        init(params = {}) {
          console.log('[FuelExpense] init called', params);
          this.resetForm();

          // bind reset
          this.resetBtn.onclick = () => this.resetForm();

          // bind save
          this.saveBtn.onclick = () => this.save();
        }

        resetForm() {
          const today = new Date().toISOString().slice(0, 10);
          this.dateEl.value  = today;
          this.amtEl.value   = '';
          this.notesEl.value = '';
        }

        async save() {
          const date  = this.dateEl.value;
          const amt   = parseFloat(this.amtEl.value);
          const notes = this.notesEl.value.trim();

          if (!date || !amt || amt <= 0) {
            alert('Enter a valid date and amount');
            return;
          }

          const payload = { date, amount: amt, notes };
          try {
            await nativeApi.post('saveFuelExpense', payload);
            navigate();
          } catch (e) {
            console.error('[FuelExpense] saveFuelExpense error', e);
            alert('Failed to save fuel expense.');
          }
        }
      },

      expose() {
        console.log('[FuelExpense] expose called');
        const app = new this.FuelExpenseApp();
        window.FuelExpenseApp = app;
        window.init_fuel_expense = app.init.bind(app);
      }
    }
  };
