package com.example.dairypos.data.repository.reference

import android.content.ContentValues
import android.util.Log
import com.example.dairypos.DatabaseHelper
import com.example.dairypos.model.StockLevel
import org.json.JSONObject

/** Stock management: recalibration, summaries, manual adjustments. No accounting. */
class r8_StockRepository(private val helper: DatabaseHelper) {
    private val db  get() = helper.writableDatabase
    private val rdb get() = helper.readableDatabase

    fun getStockSummary(): String =
        helper.fetchAll(
            rdb.rawQuery(
                """
                SELECT p.id AS productId, p.name AS productName, s.quantity,
                       u.name AS unitName, strftime('%m/%d/%Y', s.lastUpdated) lastUpdated,
                       s.unitPrice, p.lowStockThreshold
                  FROM stock s
                  JOIN products p ON s.productId = p.id
                  JOIN uoms u ON p.baseUomId = u.id
                """.trimIndent(), null
            )
        ).toString()

    fun getStockHistory(): String =
        helper.fetchAll(
            rdb.rawQuery(
                """
                SELECT p.name AS productName, s.quantity, u.name AS unitName, s.lastUpdated
                  FROM stock s
                  JOIN products p ON s.productId = p.id
                  JOIN uoms u ON s.uomId = u.id
                 ORDER BY s.lastUpdated DESC
                """.trimIndent(), null
            )
        ).toString()

    fun getRawStockSummary(): String {
        return try {
            val sql = """
                SELECT p.id AS id, p.name AS product, IFNULL(s.quantity, 0) AS quantity, u.name AS unit
                  FROM products p
                  LEFT JOIN stock s ON p.id = s.productId
                  LEFT JOIN uoms u ON u.id = s.uomId
                  LEFT JOIN categories c ON c.id = p.categoryId
                 WHERE c.name = 'Raw'
                 ORDER BY p.name
            """.trimIndent()
            JSONObject().put("stock", helper.fetchAll(rdb.rawQuery(sql, null))).toString()
        } catch (ex: Exception) {
            Log.e("r8Stock", "getRawStockSummary failed", ex)
            JSONObject().put("error", ex.message ?: "failed").toString()
        }
    }

    fun getStock(productId: String, unitId: String): String =
        helper.fetchAll(
            rdb.rawQuery(
                "SELECT * FROM ${DatabaseHelper.T_STOCK} WHERE productId = ? AND uomId = ?",
                arrayOf(productId, unitId)
            )
        ).toString()

    fun saveStock(json: String): String {
        val obj = JSONObject(json)
        val productId = obj.getInt("productId")
        val unitId    = obj.getInt("uomId")
        val cv = ContentValues().apply {
            put("productId",   productId)
            put("quantity",    obj.getDouble("quantity"))
            put("uomId",       unitId)
            put("lastUpdated", helper.nowIso())
            put("updatedOn",   System.currentTimeMillis())
        }
        val existing = db.rawQuery(
            "SELECT id FROM ${DatabaseHelper.T_STOCK} WHERE productId = ? AND uomId = ?",
            arrayOf(productId.toString(), unitId.toString())
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

        val newId = if (existing > 0) {
            db.update(DatabaseHelper.T_STOCK, cv, "productId = ? AND uomId = ?", arrayOf(productId.toString(), unitId.toString()))
            existing
        } else {
            db.insert(DatabaseHelper.T_STOCK, null, cv).toInt()
        }
        return JSONObject().put("id", newId).toString()
    }

    fun saveStockPlain(productId: Int, quantity: Double, uomId: Int): Long {
        val cursor = db.query(
            DatabaseHelper.T_STOCK, arrayOf("id", "quantity"),
            "productId = ? AND uomId = ?", arrayOf(productId.toString(), uomId.toString()),
            null, null, null
        )
        return if (cursor.moveToFirst()) {
            val existingId = cursor.getInt(0)
            val newQty     = cursor.getDouble(1) + quantity
            cursor.close()
            val cv = ContentValues().apply { put("quantity", newQty); put("lastUpdated", System.currentTimeMillis()); put("updatedOn", System.currentTimeMillis()) }
            val rows = db.update(DatabaseHelper.T_STOCK, cv, "id = ?", arrayOf(existingId.toString()))
            if (rows > 0) rows.toLong() else -1L
        } else {
            cursor.close()
            val cv = ContentValues().apply {
                put("productId", productId); put("quantity", quantity)
                put("uomId", uomId);         put("lastUpdated", System.currentTimeMillis())
                put("updatedOn", System.currentTimeMillis())
            }
            db.insert(DatabaseHelper.T_STOCK, null, cv)
        }
    }

    fun deleteStockPlain(productId: Int, uomId: Int): Boolean =
        db.delete(DatabaseHelper.T_STOCK, "productId = ? AND uomId = ?", arrayOf("$productId", "$uomId")) > 0

    fun recalibrateStock(productId: Int) {
        val level = gatherNetQuantity(productId)
        val price = calculateBaseUnitPrice(level.productId)
        val cv = ContentValues().apply {
            put("productId",   level.productId)
            put("quantity",    level.netQuantity)
            put("unitPrice",   price)
            put("uomId",       level.baseUomId)
            put("lastUpdated", helper.nowIso())
            put("updatedOn",   System.currentTimeMillis())
        }
        val existingId = db.rawQuery(
            "SELECT id FROM ${DatabaseHelper.T_STOCK} WHERE productId = ? AND uomId = ?",
            arrayOf(level.productId.toString(), level.baseUomId.toString())
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }

        if (existingId > 0) db.update(DatabaseHelper.T_STOCK, cv, "id = ?", arrayOf(existingId.toString()))
        else                 db.insert(DatabaseHelper.T_STOCK, null, cv)
    }

    fun recalculateAllStock() {
        try {
            val products = mutableListOf<Int>()
            rdb.rawQuery("SELECT DISTINCT productId FROM ${DatabaseHelper.T_STOCK}", null).use { c ->
                while (c.moveToNext()) products += c.getInt(0)
            }
            products.forEach { recalibrateStock(it) }
        } catch (ex: Exception) {
            Log.e("r8Stock", "recalculateAllStock failed", ex)
        }
    }

    fun gatherNetQuantity(productId: Int): StockLevel {
        val baseUomId = db.rawQuery(
            "SELECT baseUomId FROM ${DatabaseHelper.T_PRODUCTS} WHERE id = ?", arrayOf(productId.toString())
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

        var netQty = 0.0
        db.rawQuery(
            "SELECT transactionType, quantity, uomId FROM ${DatabaseHelper.T_TRANSACTIONS} WHERE productId = ? ORDER BY transactionDate ASC",
            arrayOf(productId.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val type  = c.getString(c.getColumnIndexOrThrow("transactionType"))
                val qty   = c.getDouble(c.getColumnIndexOrThrow("quantity"))
                val uomId = c.getInt(c.getColumnIndexOrThrow("uomId"))
                val factor = db.rawQuery(
                    "SELECT conversionFactor FROM ${DatabaseHelper.T_UNIT_CONVERSIONS} WHERE fromUomId = ? AND toUomId = ?",
                    arrayOf(uomId.toString(), baseUomId.toString())
                ).use { cc -> if (cc.moveToFirst()) cc.getDouble(0) else 1.0 }
                val baseQty = qty * factor
                netQty = if (type == "in") netQty + baseQty else netQty - baseQty
            }
        }
        return StockLevel(productId = productId, netQuantity = netQty, baseUomId = baseUomId)
    }

    fun calculateBaseUnitPrice(productId: Int): Double {
        val sql = """
            SELECT AVG(A.price * A.quantity / (C.conversionFactor * A.quantity)) AS baseUnitPrice
              FROM ${DatabaseHelper.T_PURCHASE_ITEMS} A
              JOIN ${DatabaseHelper.T_PRODUCTS} P  ON A.productId = P.id
              JOIN ${DatabaseHelper.T_UNIT_CONVERSIONS} C ON C.fromUomId = A.uomId AND C.toUomId = P.baseUomId
             WHERE A.productId = ?
        """.trimIndent()
        return try {
            rdb.rawQuery(sql, arrayOf(productId.toString())).use { c ->
                if (c.moveToFirst()) c.getDouble(c.getColumnIndexOrThrow("baseUnitPrice")) else 0.0
            }
        } catch (ex: Exception) {
            Log.e("r8Stock", "calculateBaseUnitPrice error for productId=$productId", ex)
            0.0
        }
    }
}
