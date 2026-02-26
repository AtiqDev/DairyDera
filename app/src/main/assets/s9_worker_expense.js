window.screenMap.worker_expense = {
    template: 'worker_expense.html',
    script: {
      WorkerExpenseApp: class {
        constructor() {
          this.dateEl   = document.getElementById('worker-date');
          this.amtEl    = document.getElementById('worker-amt');
          this.workerSel= document.getElementById('worker-id');
          this.notesEl  = document.getElementById('worker-notes');
          this.resetBtn = document.getElementById('resetWorkerForm');
          this.saveBtn  = document.getElementById('saveWorkerForm');
        }

        init(params = {}) {
          console.log('[WorkerExpense] init called', params);
          this.resetForm();
          this.loadWorkers();

          // bind reset
          this.resetBtn.onclick = () => {
            this.resetForm();
            this.loadWorkers();
          };

          // bind save
          this.saveBtn.onclick = () => this.save();
        }

        resetForm() {
          const today = new Date().toISOString().slice(0, 10);
          this.dateEl.value  = today;
          this.amtEl.value   = '';
          this.notesEl.value = '';
          this.workerSel.innerHTML = '<option value="" disabled selected>Select worker…</option>';
        }

        async loadWorkers() {
          try {
            const resp = await nativeApi.call('getWorkers'); // JSON string
            const list = JSON.parse(resp).workers || [];
            this.workerSel.innerHTML = '<option value="" disabled selected>Select worker…</option>';
            list.forEach(w => {
              const opt = document.createElement('option');
              opt.value = w.id;
              opt.textContent = w.name;
              this.workerSel.append(opt);
            });
          } catch (e) {
            console.error('[WorkerExpense] loadWorkers error', e);
            this.workerSel.innerHTML = '<option value="" disabled selected>Error loading</option>';
          }
        }

        async save() {
          const date     = this.dateEl.value;
          const amt      = parseFloat(this.amtEl.value);
          const workerId = this.workerSel.value;
          const notes    = this.notesEl.value.trim();

          if (!date || !workerId || !amt || amt <= 0) {
            alert('Enter a valid date, select a worker, and amount');
            return;
          }

          const payload = { date, workerId, amount: amt, notes };
          try {
            await nativeApi.post('saveLaborExpense', payload);
            navigate();
          } catch (e) {
            console.error('[WorkerExpense] saveLaborExpense error', e);
            alert('Failed to save labor expense.');
          }
        }
      },

      expose() {
        console.log('[WorkerExpense] expose called');
        const app = new this.WorkerExpenseApp();
        window.WorkerExpenseApp = app;
        window.init_worker_expense = app.init.bind(app);
      }
    }
  };
