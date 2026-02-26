// --- message_port.js ---

window.nativeApi = {
    _port: null,
    _callbacks: {},
    _callbackCounter: 0,

    _initPromise: null,

    // --- Initialization ---
    init() {
        if (this._initPromise) return this._initPromise;

        this._initPromise = new Promise((resolve, reject) => {
            if (this._port) {
                console.log("✅ Port already exists.");
                resolve();
                return;
            }

            console.log("🛠 Waiting for initPort message...");

            const messageListener = (event) => {
                console.log("📩 window.onmessage triggered:", event.data);
                if (event.data === "initPort") {
                    this._port = event.ports[0];
                    this._port.onmessage = this._handleMessage.bind(this);
                    if (this._port.start) this._port.start();
                    console.log("✅ MessagePort initialized and onmessage attached.");
                    window.removeEventListener("message", messageListener);
                    resolve();
                }
            };

            window.addEventListener("message", messageListener);

            // Timeout for safety
            setTimeout(() => {
                if (!this._port) {
                    console.error("🚨 MessagePort initialization timed out. No 'initPort' message received.");
                    this._initPromise = null; // Allow retry
                    reject("Timeout");
                }
            }, 5000); // 5-second timeout for slower devices
        });

        return this._initPromise;
    },

    // --- Message Handler ---
    _handleMessage(event) {
        try {
            const response = JSON.parse(event.data);
            const { callbackId, data, error } = response;

            if (callbackId && this._callbacks[callbackId]) {
                if (error) {
                    console.error(`Native Error (cb:${callbackId}):`, error);
                    this._callbacks[callbackId].reject(error);
                } else {
                    this._callbacks[callbackId].resolve(data);
                }
                delete this._callbacks[callbackId]; // Clean up
            } else {
                console.warn("Received message with no matching callback:", response);
            }
        } catch (e) {
            console.error("JS Error handling native message:", e, "Original data:", event.data);
        }
    },

    // --- Public API Call ---
    /**
     * Calls a native action and returns a Promise with the result.
     * @param {string} action - The name of the native action to call.
     * @param {object} [payload] - Optional data to send with the action.
     * @returns {Promise<any>}
     */
    call(action, payload = {}) {
        return new Promise(async (resolve, reject) => {
            if (!this._port) {
                try {
                    await this.init();
                } catch (e) {
                    return reject("MessagePort not initialized");
                }
            }

            const callbackId = `cb_${this._callbackCounter++}_${Date.now()}`;
            this._callbacks[callbackId] = { resolve, reject };

            const request = {
                action,
                payload,
                callbackId
            };

            try {
                this._port.postMessage(JSON.stringify(request));
            } catch (e) {
                console.error("Error posting message to port:", e);
                delete this._callbacks[callbackId];
                reject(e);
            }

            // Safety timeout
            setTimeout(() => {
                if (this._callbacks[callbackId]) {
                    reject(new Error(`Native call '${action}' timed out.`));
                    delete this._callbacks[callbackId];
                }
            }, 10000); // 10-second timeout
        });
    },

    // --- Fire-and-forget version ---
    /**
     * Sends a message to native without waiting for a response.
     * @param {string} action - The name of the native action to call.
     * @param {object} [payload] - Optional data to send with the action.
     */
    async post(action, payload = {}) {
         if (!this._port) {
             try {
                 await this.init();
             } catch (e) {
                 console.error("Cannot post message, port not initialized.");
                 return;
             }
         }
         try {
            this._port.postMessage(JSON.stringify({ action, payload }));
         } catch (e) {
             console.error("Error posting message:", e);
         }
    }
};

// --- Optional: Initialize immediately ---
window.nativeApi.init().catch(err => console.error("Failed to auto-init nativeApi:", err));
