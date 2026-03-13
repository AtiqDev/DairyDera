package com.example.dairypos.data.repository.sync

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.example.dairypos.DatabaseHelper
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manages server sync for DairyPOS.
 *
 * Strategy: timestamp-based, pull-from-device.
 *   - Every sync table carries `updatedOn` (Unix ms) and `isDeleted` columns.
 *   - On sync, rows where updatedOn > lastSyncTime are sent as full snapshots.
 *   - First sync (lastSyncTime = null) sends all rows from every table.
 *   - Server receives the payload and UPSERTs each row into its mirror table.
 *   - Soft deletes: delete operations set isDeleted=1, updatedOn=now instead of physically removing rows.
 */
class SyncManager(private val helper: DatabaseHelper) {

    private val rdb get() = helper.readableDatabase
    private val db  get() = helper.writableDatabase

    // All tables that participate in sync.
    private val SYNC_TABLES = listOf(
        "purchases", "purchaseItems",
        "sales", "invoice", "invoiceSaleItems",
        "paymentReceived", "paymentApplied", "payablePayment",
        "productionBatches", "productionBatchLines",
        "transactions", "stock",
        "journalEntries", "accountingTransaction",
        "fuelRecords", "electricityBills",
        "dailyOperationalExpenses", "monthlyOperationalCosts",
        "dailyRentalAllocation", "dailyLaborWages",
        "animalTransactions", "animalHealthEvents", "animalReproduction", "animalLactation",
        "customers", "customerLocations",
        "suppliers", "supplierItems",
        "employees", "products", "leases",
        "animals", "animalGroups", "vaccinationSchedules", "SellableProductRates"
    )

    // ── syncMeta helpers ──────────────────────────────────────────────────────

    fun getMeta(key: String): String =
        rdb.rawQuery("SELECT value FROM syncMeta WHERE key=?", arrayOf(key))
            .use { if (it.moveToFirst()) it.getString(0) ?: "" else "" }

    fun saveMeta(key: String, value: String) {
        db.insertWithOnConflict("syncMeta", null, ContentValues().apply {
            put("key", key); put("value", value)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getSyncMeta(): String {
        return JSONObject().apply {
            put("serverUrl",    getMeta("server_url"))
            put("deviceId",     getMeta("device_id"))
            put("apiKey",       getMeta("api_key"))
            put("lastSyncTime", getMeta("last_sync_time").toLongOrNull() ?: JSONObject.NULL)
        }.toString()
    }

    fun saveSyncMetaFromJson(json: String) {
        val obj = JSONObject(json)
        if (obj.has("serverUrl"))    saveMeta("server_url",      obj.getString("serverUrl"))
        if (obj.has("deviceId"))     saveMeta("device_id",       obj.getString("deviceId"))
        if (obj.has("apiKey"))       saveMeta("api_key",         obj.getString("apiKey"))
    }

    // ── Payload builder ───────────────────────────────────────────────────────

    /** Builds the JSON payload to POST to the server. */
    fun buildPayload(): JSONObject {
        val lastSyncTime = getMeta("last_sync_time").toLongOrNull()
        val deviceId     = getMeta("device_id")
        val tables       = JSONArray()

        for (tableName in SYNC_TABLES) {
            val rows = fetchChangedRows(tableName, lastSyncTime)
            if (rows.length() == 0) continue

            tables.put(JSONObject().apply {
                put("name", tableName)
                put("rows", rows)
            })
        }

        return JSONObject().apply {
            put("device_id",  deviceId)
            put("sync_time",  helper.nowIso())
            put("tables",     tables)
        }
    }

    private fun fetchChangedRows(tableName: String, lastSyncTime: Long?): JSONArray {
        val cursor = if (lastSyncTime != null) {
            rdb.rawQuery(
                "SELECT * FROM $tableName WHERE updatedOn > ?",
                arrayOf(lastSyncTime.toString())
            )
        } else {
            rdb.rawQuery("SELECT * FROM $tableName", null)
        }
        return helper.fetchAll(cursor)
    }

    // ── Sync execution ────────────────────────────────────────────────────────

    /**
     * Builds the payload, sends it to the server, and on success updates last_sync_time.
     * Returns a result JSON: { synced: N, error: "..." }
     * Must be called from a background thread (IO coroutine).
     */
    fun runSync(): String {
        val serverUrl = getMeta("server_url")
        val apiKey    = getMeta("api_key")

        if (serverUrl.isEmpty() || apiKey.isEmpty()) {
            return JSONObject().put("error", "Sync not configured. Set server URL and API key first.").toString()
        }

        val payload = buildPayload()

        // Check if there is anything to send
        val tables = payload.getJSONArray("tables")
        if (tables.length() == 0) {
            return JSONObject().put("synced", 0).put("message", "Nothing to sync.").toString()
        }

        val client   = SyncApiClient()
        val response = client.push(serverUrl, apiKey, payload)

        return when {
            response == null -> {
                JSONObject().put("error", "Server unreachable. Check URL and network.").toString()
            }
            response.has("error") -> {
                JSONObject().put("error", response.getString("error")).toString()
            }
            else -> {
                // Record the sync time so the next incremental sync only sends new rows
                saveMeta("last_sync_time", System.currentTimeMillis().toString())
                JSONObject().apply {
                    put("synced",  response.optInt("upserted", 0))
                    put("message", "Sync successful.")
                }.toString()
            }
        }
    }
}
