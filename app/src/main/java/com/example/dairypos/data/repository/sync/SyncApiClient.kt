package com.example.dairypos.data.repository.sync

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class SyncApiClient {

    /** Pushes a sync payload to the server. Returns the server response or null on network failure. */
    fun push(serverUrl: String, apiKey: String, payload: JSONObject): JSONObject? {
        return try {
            val conn = (URL("$serverUrl/sync/push").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-API-Key", apiKey)
                doOutput      = true
                connectTimeout = 15_000
                readTimeout    = 30_000
            }

            val body = payload.toString().toByteArray(Charsets.UTF_8)
            conn.outputStream.use { it.write(body) }

            val code = conn.responseCode

            if (code == 200) {
                val response = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                JSONObject(response)
            } else {
                // Read the error body so the user sees the actual server message
                val errBody = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                val serverMsg = if (!errBody.isNullOrBlank()) {
                    runCatching { JSONObject(errBody).optString("error", errBody) }.getOrDefault(errBody)
                } else {
                    "HTTP $code"
                }
                JSONObject().put("error", serverMsg)
            }
        } catch (e: Exception) {
            null
        }
    }
}
