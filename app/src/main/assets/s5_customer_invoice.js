// s2_customer_invoice.js
window.screenMap.customer_invoice = {
    template: 'customer_invoice.html',
    script: {
      // --- Helpers ---
      _isNumericCell(val) {
        if (val == null) return false;
        return /^-?\d+(?:[\d,]*\d)?(?:\.\d+)?$/.test(String(val).replace(/\s/g, ""));
      },
      _isPaymentRow(row) {
        if (!row) return false;
        return row.some(
          (c) =>
            typeof c === "string" &&
            /(bank|iban|account|payment|method)/i.test(c)
        );
      },

      // --- App class ---
      CustomerInvoiceApp: class {
        constructor() {
          this.custSelect = null;
          this.monthSelect = null;
        }

        async init(params = {}) {
          console.log("🟢 Initializing Customer Invoice screen...", params);
          this.custSelect = document.getElementById("customerSelect");
          this.monthSelect = document.getElementById("monthSelect");

          // Populate month dropdown
          const monthNames = [
            "January","February","March","April","May","June",
            "July","August","September","October","November","December"
          ];
          this.monthSelect.innerHTML = monthNames
            .map((m, i) => `<option value="${i + 1}">${m}</option>`)
            .join("");
          const currentMonth = new Date().getMonth() + 1;
          this.monthSelect.value = currentMonth;

          // Populate customer dropdown
          let customers = [];
          try {
            customers = JSON.parse(await nativeApi.call('getCustomers'));
          } catch (e) {
            console.warn("⚠️ nativeApi.call('getCustomers') failed. Using sample data.");
            customers = [
              { id: 1, name: "Atiq" },
              { id: 2, name: "Ahmed" },
            ];
          }

          this.custSelect.innerHTML = customers
            .map((c) => `<option value="${c.id}">${c.name || "Customer " + c.id}</option>`)
            .join("");

          if (customers.length > 0) {
            this.custSelect.value = customers[0].id;
            this.renderInvoiceForCustomer(customers[0].id, currentMonth);
          }

          // Change handlers
          this.custSelect.addEventListener("change", () => {
            this.renderInvoiceForCustomer(this.custSelect.value, this.monthSelect.value);
          });
          this.monthSelect.addEventListener("change", () => {
            this.renderInvoiceForCustomer(this.custSelect.value, this.monthSelect.value);
          });
        }

        async renderInvoiceForCustomer(customerId, monthId) {
          console.log(`📄 Rendering invoice for customer ${customerId}, month ${monthId}`);

          const custIdInt = parseInt(customerId, 10);
          const monthIdInt = parseInt(monthId, 10);

          const titleEl = document.getElementById("report-title");
          const detailsEl = document.getElementById("invoice-details");
          const theadEl = document.getElementById("invoice-thead");
          const tbodyEl = document.getElementById("invoice-tbody");
          const paymentEl = document.getElementById("payment-details");

          titleEl.textContent = "Loading Invoice...";
          detailsEl.innerHTML = "";
          theadEl.innerHTML = "";
          tbodyEl.innerHTML = "";
          paymentEl.innerHTML = "";

          // Call backend
          let jsonString = null;
          try {
            jsonString = await nativeApi.call('getCustomerInvoiceDataString', {customerId: custIdInt, monthId: monthIdInt});
          } catch (ex) {
            console.warn("⚠️ Using sample data due to nativeApi call failure.");
            jsonString = JSON.stringify({
              status: "success",
              data: [
                [`Sample Invoice for ${monthId}`],
                ["From Atiq Contact # 0000 0606700"],
                ["Bill To:", "Atiq"],
                [],
                ["From", "To", "Days", "Qty", "Total Qty", "amount"],
                ["12-Oct", "12-Oct", "1.0", "7.5", "7.5", "Rs 1,650.00"],
                [null, null, null, "TOTAL", "7.5", "Rs 1,650.00"],
                [null, null, "Payment Method"],
                [null, null, "Bank name: Standard Chartered"],
              ],
            });
          }

          // Parse & Render
          let response;
          try {
            response = JSON.parse(jsonString);
          } catch (ex) {
            titleEl.textContent = "Data Parsing Error";
            detailsEl.innerHTML = `<p class="text-danger">Invalid data received.</p>`;
            return;
          }

          if (!response || response.status !== "success" || !Array.isArray(response.data)) {
            titleEl.textContent = "Error Loading Invoice";
            detailsEl.innerHTML = `<p class="text-danger">Failed to retrieve data.</p>`;
            return;
          }

          const rows = response.data;
          if (rows.length === 0) {
            titleEl.textContent = "No data";
            return;
          }

          titleEl.textContent = rows[0][0] || "Invoice";

          rows.forEach((r, i) => {
            if (!Array.isArray(r)) return;
            const hasContent = r.some((c) => c && String(c).trim().length > 0);
            if (!hasContent) return;

            if (i < 4 && !window.screenMap.customer_invoice.script._isPaymentRow(r)) {
              const parts = r.map((c) => (c ? String(c).trim() : "")).filter((s) => s.length > 0);
              if (parts.length > 0) {
                detailsEl.insertAdjacentHTML("beforeend", `<p class="invoice-meta">${parts.join(" ")}</p>`);
                return;
              }
            }

            if (window.screenMap.customer_invoice.script._isPaymentRow(r)) {
              const parts = r.map((c) => (c ? String(c).trim() : "")).filter((s) => s.length > 0);
              if (parts.length > 0) {
                if (!paymentEl.innerHTML) paymentEl.innerHTML = '<h6 class="fw-semibold">Payment Details</h6>';
                paymentEl.insertAdjacentHTML("beforeend", `<p class="mb-0">${parts.join(" ")}</p>`);
              }
              return;
            }

            const cellsHtml = r
              .map((c) => {
                const raw = c == null ? "" : String(c);
                if (/^rs/i.test(raw.trim())) return `<td class="text-end">${raw}</td>`;
                if (window.screenMap.customer_invoice.script._isNumericCell(raw))
                  return `<td class="text-end">${Number(String(raw).replace(/,/g, "")).toLocaleString()}</td>`;
                return `<td>${raw}</td>`;
              })
              .join("");
            tbodyEl.insertAdjacentHTML("beforeend", `<tr>${cellsHtml}</tr>`);
          });
        }
      },

      // --- Expose ---
      expose() {
        console.log("[CustomerInvoice] expose called");
        window.CustomerInvoiceApp = new this.CustomerInvoiceApp();
        window.init_customer_invoice = window.CustomerInvoiceApp.init.bind(window.CustomerInvoiceApp);
      }
    }
  };
