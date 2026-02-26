window.screenMap.query = {
    template: 'query.html',
    script: {
      QueryApp: class {
        constructor() {
          this.editor = null;
          this.refs = {};
        }

        async init(params = {}) {
          console.log('[Query] init called', params);
          this.refs = {
            resultsDiv: document.getElementById('query-results'),
            selectEl: document.getElementById('table-list'),
            undoBtn: document.getElementById('btnUndo'),
            execBtn: document.getElementById('execute-query'),
            ta: document.getElementById('raw-query'),
            editorHost: document.getElementById('editor')
          };

          try {
            await this.loadCSS('codemirror/lib/codemirror.min.css');
            await this.loadScript('codemirror/lib/codemirror.min.js');
            await this.loadScript('codemirror/mode/sql/sql.min.js');
            await this.loadScript('codemirror/addon/edit/matchbrackets.min.js');
          } catch (err) {
            this.refs.resultsDiv.innerHTML = `<p class="text-danger">${err}</p>`;
            return;
          }

          this.editor = CodeMirror.fromTextArea(this.refs.ta, {
            mode: 'text/x-mysql',
            lineNumbers: true,
            matchBrackets: true,
            lineWrapping: true,
            theme: 'default'
          });
          this.editor.setSize('100%', '200px');

          this.bindEvents();
          this.loadTables();
        }

        bindEvents() {
          // Undo
          this.refs.undoBtn.onclick = () => {
            this.editor.undo();
            this.editor.focus();
          };

          // Table selection -> seed editor
          this.refs.selectEl.onchange = () => {
            const tbl = this.refs.selectEl.value;
            if (!tbl) return;
            this.editor.setValue(`SELECT * FROM ${tbl};`);
            this.editor.focus();
            const lastLine = this.editor.lineCount() - 1;
            const lastCh = this.editor.getLine(lastLine).length;
            this.editor.setCursor(lastLine, lastCh);
            this.refs.selectEl.selectedIndex = 0;
            this.validate();
          };

          // Live validation
          this.editor.on('change', () => this.validate());

          // Execute
          this.refs.execBtn.onclick = () => this.execute();
        }

        async loadTables() {
          let namesJson;
          try {
            namesJson = await nativeApi.call('getTableNames');
          } catch (e) {
            this.refs.resultsDiv.innerHTML = `<p class="text-danger">Table lookup failed: ${e}</p>`;
            return;
          }

          const tables = JSON.parse(namesJson);
          tables.forEach(t => {
            const opt = document.createElement('option');
            opt.value = t;
            opt.textContent = t;
            this.refs.selectEl.appendChild(opt);
          });
        }

        validate() {
          // Clear previous marks
          this.editor.getAllMarks().forEach(m => m.clear());
          const sql = this.editor.getValue();
          const stack = [];
          for (let i = 0; i < sql.length; i++) {
            if (sql[i] === '(') stack.push(i);
            if (sql[i] === ')') {
              if (!stack.length) {
                this.markError(i);
                return false;
              }
              stack.pop();
            }
          }
          if (stack.length) {
            this.markError(stack.pop());
            return false;
          }
          return true;
        }

        markError(idx) {
          const pos = this.editor.posFromIndex(idx);
          this.editor.markText(
            { line: pos.line, ch: 0 },
            { line: pos.line, ch: this.editor.getLine(pos.line).length },
            { className: 'cm-error-line' }
          );
        }

        async execute() {
          const sql = this.editor.getValue().trim();
          if (!this.validate()) {
            this.refs.resultsDiv.innerHTML = '<p class="text-danger">Syntax error: unbalanced parentheses.</p>';
            return;
          }

          let res;
          try {
            res = await nativeApi.call('executeQuery', {sql});
          } catch (e) {
            this.refs.resultsDiv.innerHTML = `<p class="text-danger">Bridge error: ${e}</p>`;
            return;
          }

          const data = JSON.parse(res);
          if (data.error) {
            this.refs.resultsDiv.innerHTML = `<p class="text-danger">${data.error}</p>`;
            return;
          }
          if (!data.length) {
            this.refs.resultsDiv.innerHTML = '<p>No records found.</p>';
            return;
          }

          const cols = Object.keys(data[0]);
          let html = '<table class="table table-sm table-bordered"><thead><tr>';
          cols.forEach(c => (html += `<th>${c}</th>`));
          html += '</tr></thead><tbody>';
          data.forEach(row => {
            html += '<tr>';
            cols.forEach(c => (html += `<td>${row[c] ?? ''}</td>`));
            html += '</tr>';
          });
          html += '</tbody></table>';
          this.refs.resultsDiv.innerHTML = html;
        }

        // Utilities
        loadCSS(href) {
          return new Promise((resolve, reject) => {
            const link = document.createElement('link');
            link.rel = 'stylesheet';
            link.href = href;
            link.onload = resolve;
            link.onerror = () => reject(`CSS failed: ${href}`);
            document.head.appendChild(link);
          });
        }

        loadScript(src) {
          return new Promise((resolve, reject) => {
            const s = document.createElement('script');
            s.src = src;
            s.onload = resolve;
            s.onerror = () => reject(`Script failed: ${src}`);
            document.head.appendChild(s);
          });
        }
      },

      expose() {
        console.log('[Query] expose called');
        const app = new this.QueryApp();
        window.QueryApp = app;
        window.init_query = app.init.bind(app);
      }
    }
  };
