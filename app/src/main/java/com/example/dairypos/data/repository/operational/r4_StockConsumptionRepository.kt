package com.example.dairypos.data.repository.operational

import android.util.Log
import com.example.dairypos.DatabaseHelper
import com.example.dairypos.data.repository.contracts.IAccountingEngine
import com.example.dairypos.data.repository.contracts.OperationalEvent
import org.json.JSONObject

/** Operational — feed/ingredient consumption. Triggers FeedUse journal entries. */
class r4_StockConsumptionRepository(
    private val helper: DatabaseHelper,
    private val engine: IAccountingEngine
) {
    private val db  get() = helper.writableDatabase
    private val rdb get() = helper.readableDatabase

    fun saveConsumption(json: String): String {
        return try {
            val obj       = JSONObject(json)
            val productId = obj.getInt("productId")
            val txnType   = obj.optString("transactionType", "out")
            val quantity  = obj.getDouble("quantity")
            val unitPrice = calculateBaseUnitPrice(productId)
            val unitId    = obj.getInt("unitId")
            val notes     = obj.optString("notes", "")
            val txnDate   = obj.optString("timestamp", helper.nowIso())

            val batchId = helper.getBatchId()
            val rowId = helper.insertTransaction(
                refType   = "FeedConsumed",
                refId     = batchId,
                productId = productId,
                lineId    = 1,
                txnType   = txnType,
                quantity  = quantity,
                uomId     = unitId,
                txnDate   = txnDate,
                notes     = notes
            )

            engine.processEvent(OperationalEvent(
                transactionTypeName = "FeedUse",
                subType             = null,
                tableName           = "ProductionBatches",
                entityId            = batchId,
                amount              = unitPrice * quantity,
                productId           = productId,
                unitPrice           = unitPrice
            ))

            helper.recalibrateStock(productId)

            JSONObject().put("id", rowId).toString()
        } catch (ex: Exception) {
            Log.e("r4Consumption", "saveConsumption failed", ex)
            JSONObject().put("error", ex.message ?: "Unknown error").toString()
        }
    }

    private fun calculateBaseUnitPrice(productId: Int): Double {
        val sql = """
            SELECT AVG(A.price * A.quantity / (C.conversionFactor * A.quantity)) AS baseUnitPrice
              FROM ${DatabaseHelper.T_PURCHASE_ITEMS} A
              JOIN ${DatabaseHelper.T_PRODUCTS} P ON A.productId = P.id
              JOIN ${DatabaseHelper.T_UNIT_CONVERSIONS} C
                ON C.fromUomId = A.uomId AND C.toUomId = P.baseUomId
             WHERE A.productId = ?
        """.trimIndent()
        return try {
            rdb.rawQuery(sql, arrayOf(productId.toString())).use { cursor ->
                if (cursor.moveToFirst()) cursor.getDouble(cursor.getColumnIndexOrThrow("baseUnitPrice")) else 0.0
            }
        } catch (ex: Exception) {
            Log.e("r4Consumption", "calculateBaseUnitPrice error for productId=$productId", ex)
            0.0
        }
    }
}
