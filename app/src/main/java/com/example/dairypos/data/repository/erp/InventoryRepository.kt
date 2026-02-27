package com.example.dairypos.data.repository.erp

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.example.dairypos.DatabaseHelper
import com.example.dairypos.model.*
import com.example.dairypos.CustomerDto
import org.json.JSONArray
import org.json.JSONObject
import com.google.gson.Gson
import java.time.*
import java.time.format.DateTimeFormatter
import androidx.core.database.getDoubleOrNull
import androidx.core.database.getIntOrNull
import android.content.ContentValues

class InventoryRepository(private val helper: DatabaseHelper) {
    private val db get() = helper.writableDatabase
    private val rdb get() = helper.readableDatabase

    fun getAllUnits(): String =
        helper.fetchAll(rdb.rawQuery("SELECT * FROM ${DatabaseHelper.T_UOMS}", null)).toString()

    fun saveUnit(json: String): String {
        val obj = JSONObject(json)
        val id = obj.optInt("id", 0)
        val cv = ContentValues().apply {
            put("name", obj.getString("name"))
            put("type", obj.getString("type"))
        }
        val newId = db.use { db ->
            if (id > 0) {
                db.update(DatabaseHelper.T_UOMS, cv, "id=?", arrayOf(id.toString()))
                id
            } else {
                db.insert(DatabaseHelper.T_UOMS, null, cv).toInt()
            }
        }
        return JSONObject().put("id", newId).toString()
    }

    fun deleteUnit(id: Int): String {
        val rowsDeleted = db.delete(
            DatabaseHelper.T_UOMS,
            "id = ?",
            arrayOf(id.toString())
        )
        return JSONObject().put("deleted", rowsDeleted).toString()
    }

    fun getUomId(db: SQLiteDatabase, name: String): Long? {
        val projection = arrayOf("id")
        val selection = "name = ?"
        val selectionArgs = arrayOf(name)
        db.query("uoms", projection, selection, selectionArgs, null, null, null).use { cursor ->
            return if (cursor.moveToFirst()) {
                cursor.getLong(cursor.getColumnIndexOrThrow("id"))
            } else {
                null
            }
        }
    }

    fun getUOMList(): String =
        helper.fetchAll(rdb.rawQuery("SELECT id, name FROM ${DatabaseHelper.T_UOMS}", null)).toString()

    fun getAllProducts(): String =
        helper.fetchAll(
            rdb.rawQuery(
                """
            SELECT p.*, u.name as unitName
            FROM ${DatabaseHelper.T_PRODUCTS} p
            JOIN ${DatabaseHelper.T_UOMS} u ON p.baseUomId = u.id
        """, null
            )
        ).toString()

    fun getSellableProducts(): String =
        helper.fetchAll(
            rdb.rawQuery(
                """
            SELECT p.*, u.name as unitName, p.baseUomId AS unitId
            FROM ${DatabaseHelper.T_PRODUCTS} p
            JOIN ${DatabaseHelper.T_UOMS} u ON p.baseUomId = u.id
            JOIN ${DatabaseHelper.T_CATEGORY}  c ON p.categoryId  = c.id
            WHERE c.name IN ('Product','Raw')
        """, null
            )
        ).toString()

    fun getProduct(id: String): String =
        helper.fetchAll(
            rdb.rawQuery(
                """
            SELECT p.id, p.name, p.description, p.baseUomId
            FROM ${DatabaseHelper.T_PRODUCTS} p WHERE id = ?
        """, arrayOf(id)
            )
        ).toString()

    fun saveProduct(json: String): String {
        val obj = JSONObject(json)
        val id = obj.optInt("id", 0)

        val cv = ContentValues().apply {
            put("name", obj.getString("productName"))
            put("description", obj.getString("description"))
            put("baseUomId", obj.getInt("baseUnitId"))
        }

        val newId = db.use { db ->
            if (id > 0) {
                db.update(
                    "products",
                    cv,
                    "id = ?",
                    arrayOf(id.toString())
                )
                id
            } else {
                db.insert(DatabaseHelper.T_PRODUCTS, null, cv).toInt()
            }
        }

        return JSONObject().put("id", newId).toString()
    }

    fun deleteProduct(id: Int): Boolean {
        db.use { db ->
            val rowsDeleted = db.delete(
                DatabaseHelper.T_PRODUCTS,
                "id = ?",
                arrayOf(id.toString())
            )
            return rowsDeleted > 0
        }
    }

    fun getProductId(db: SQLiteDatabase, name: String): Long? {
        val projection = arrayOf("id")
        val selection = "name = ?"
        val selectionArgs = arrayOf(name)
        db.query("products", projection, selection, selectionArgs, null, null, null).use { cursor ->
            return if (cursor.moveToFirst()) {
                cursor.getLong(cursor.getColumnIndexOrThrow("id"))
            } else {
                null
            }
        }
    }

    fun getProductBaseUnit(productId: String): String =
        helper.fetchAll(
            rdb.rawQuery(
                """
            SELECT baseUomId
            FROM ${DatabaseHelper.T_PRODUCTS}
            WHERE id = ?
        """, arrayOf(productId)
            )
        ).toString()

    fun getProductIdByCategory(categoryName: String): Int {
        val queryProdId = """
        SELECT _prod.id
        FROM ${DatabaseHelper.T_PRODUCTS} _prod
        JOIN ${DatabaseHelper.T_CATEGORY} _cat ON _prod.categoryId = _cat.id
        WHERE _cat.name = ?
    """.trimIndent()

        val productId = helper.fetchScalarInt(queryProdId, arrayOf(categoryName))

        return productId
    }

    fun getBaseUomId(productId: Int): Int {
        val db = rdb
        db.rawQuery(
            "SELECT baseUomId FROM ${DatabaseHelper.T_PRODUCTS} WHERE id = ?",
            arrayOf(productId.toString())
        ).use { c ->
            if (c.moveToFirst()) return c.getInt(0)
        }
        return 0
    }

    fun getConversionFactor(fromUomId: Int, toUomId: Int): Double {
        val db = rdb
        db.rawQuery(
            """
      SELECT conversionFactor
        FROM ${DatabaseHelper.T_UNIT_CONVERSIONS}
       WHERE fromUomId = ? AND toUomId = ?
    """.trimIndent(),
            arrayOf(fromUomId.toString(), toUomId.toString())
        ).use { c ->
            if (c.moveToFirst()) {
                return c.getDouble(0)
            }
        }
        return 1.0
    }

    fun getAllConversions(): String =
        helper.fetchAll(
            rdb.rawQuery(
                """
            SELECT uc.*, u1.name as fromName, u2.name as toName
            FROM ${DatabaseHelper.T_UNIT_CONVERSIONS} uc
            JOIN ${DatabaseHelper.T_UOMS} u1 ON uc.fromUomId = u1.id
            JOIN ${DatabaseHelper.T_UOMS} u2 ON uc.toUomId = u2.id
        """, null
            )
        ).toString()

    fun saveConversion(json: String): String {
        val data = JSONObject(json)
        val db = this.db
        val values = ContentValues().apply {
            put("fromUomId", data.getInt("fromUomId"))
            put("toUomId", data.getInt("toUomId"))
            put("conversionFactor", data.getDouble("conversionFactor"))
        }
        val id = data.optInt("id", 0)

        return try {
            if (id > 0) {
                val rowsAffected = db.update(
                    DatabaseHelper.T_UNIT_CONVERSIONS,
                    values,
                    "id = ?",
                    arrayOf(id.toString())
                )
                if (rowsAffected > 0) {
                    JSONObject().put("id", id).toString()
                } else {
                    val existingId = db.rawQuery(
                        "SELECT id FROM ${DatabaseHelper.T_UNIT_CONVERSIONS} WHERE fromUomId = ? AND toUomId = ?",
                        arrayOf(
                            data.getInt("fromUomId").toString(),
                            data.getInt("toUomId").toString()
                        )
                    ).use { cursor ->
                        if (cursor.moveToFirst()) cursor.getInt(0) else 0
                    }
                    if (existingId > 0) {
                        db.update(
                            DatabaseHelper.T_UNIT_CONVERSIONS,
                            values,
                            "id = ?",
                            arrayOf(existingId.toString())
                        )
                        JSONObject().put("id", existingId).toString()
                    } else {
                        val newId = db.insert(DatabaseHelper.T_UNIT_CONVERSIONS, null, values)
                        JSONObject().put("id", newId).toString()
                    }
                }
            } else {
                val newId = db.insert(DatabaseHelper.T_UNIT_CONVERSIONS, null, values)
                JSONObject().put("id", newId).toString()
            }
        } catch (e: Exception) {
            JSONObject().put("status", "error").put("message", e.message).toString()
        }
    }

    fun deleteConversion(conversionId: Int): Boolean {
        db.use { db ->
            val rowsDeleted = db.delete(
                DatabaseHelper.T_UNIT_CONVERSIONS,
                "id = ?",
                arrayOf(conversionId.toString())
            )
            return rowsDeleted > 0
        }
    }

    fun getConversion(fromUnit: String, toUnit: String): String =
        helper.fetchAll(
            rdb.rawQuery(
                """
            SELECT ConversionFactor
            FROM ${DatabaseHelper.T_UNIT_CONVERSIONS}
            WHERE fromUomId = ? AND toUomId = ?
        """, arrayOf(fromUnit, toUnit)
            )
        ).toString()

    fun getStock(productId: String, unitId: String): String =
        helper.fetchAll(
            rdb.rawQuery(
                """
            SELECT *
            FROM ${DatabaseHelper.T_STOCK}
            WHERE productId = ? AND uomId = ?
        """, arrayOf(productId, unitId)
            )
        ).toString()

    fun saveStock(json: String): String {
        val obj = JSONObject(json)
        val productId = obj.getInt("productId")
        val unitId = obj.getInt("uomId")
        val cv = ContentValues().apply {
            put("productId", productId)
            put("quantity", obj.getDouble("quantity"))
            put("uomId", unitId)
            put("lastUpdated", helper.nowIso())
        }
        val newId = db.use { db ->
            val existing = db.rawQuery(
                "SELECT id FROM ${DatabaseHelper.T_STOCK} WHERE productId = ? AND uomId = ?",
                arrayOf(productId.toString(), unitId.toString())
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }
            if (existing > 0) {
                db.update(
                    DatabaseHelper.T_STOCK,
                    cv,
                    "productId = ? AND uomId = ?",
                    arrayOf(productId.toString(), unitId.toString())
                )
                existing
            } else {
                db.insert(DatabaseHelper.T_STOCK, null, cv).toInt()
            }
        }
        return JSONObject().put("id", newId).toString()
    }

    fun saveStockOld(json: String): String {
        val data = JSONObject(json)
        val values = ContentValues().apply {
            put("productId", data.getInt("productId"))
            put("quantity", data.getDouble("quantity"))
            put("uomId", data.getInt("unitId"))
        }
        db.insertWithOnConflict(
            DatabaseHelper.T_STOCK,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
        return JSONObject().put("status", "success").toString()
    }

    fun saveStockPlain(productId: Int, quantity: Double, uomId: Int): Long {
        val db = this.db

        val cursor = db.query(
            DatabaseHelper.T_STOCK,
            arrayOf("id", "quantity"),
            "productId = ? AND uomId = ?",
            arrayOf(productId.toString(), uomId.toString()),
            null, null, null
        )

        return if (cursor.moveToFirst()) {
            val existingId = cursor.getInt(0)
            val existingQty = cursor.getDouble(1)
            cursor.close()

            val newQty = existingQty + quantity
            val cv = ContentValues().apply {
                put("quantity", newQty)
                put("lastUpdated", System.currentTimeMillis())
            }

            val rows = db.update(
                DatabaseHelper.T_STOCK,
                cv,
                "id = ?",
                arrayOf(existingId.toString())
            )

            if (rows > 0) rows.toLong() else -1L

        } else {
            cursor.close()
            val cv = ContentValues().apply {
                put("productId", productId)
                put("quantity", quantity)
                put("uomId", uomId)
                put("lastUpdated", System.currentTimeMillis())
            }
            db.insert(DatabaseHelper.T_STOCK, null, cv)
        }
    }

    fun deleteStockPlain(productId: Int, uomId: Int): Boolean {
        val db = this.db
        val deleted = db.delete(
            DatabaseHelper.T_STOCK,
            "productId = ? AND uomId = ?",
            arrayOf("$productId", "$uomId")
        )
        return deleted > 0
    }

    fun recalculateAllStock() {
        try {
            val db = rdb
            val products = mutableListOf<Int>()

            db.rawQuery("SELECT DISTINCT productId FROM ${DatabaseHelper.T_STOCK}", null).use { c ->
                while (c.moveToNext()) {
                    products += c.getInt(0)
                }
            }

            val results = JSONArray()
            products.forEach { pid ->
                recalibrateStock(pid)
            }

        } catch (ex: Exception) {
            android.util.Log.e("DB", "recalculateAllBasePrices failed", ex)
            JSONObject()
                .put("error", ex.message ?: "batch calculation failed")
                .toString()
        }
    }

    fun calculateBaseUnitPrice(productId: Int): Double {
        val db = rdb
        val sql = """
    SELECT
      AVG(A.price * A.quantity/ (C.conversionFactor * A.quantity)) AS baseUnitPrice
    FROM ${DatabaseHelper.T_PURCHASE_ITEMS} A
    JOIN ${DatabaseHelper.T_PRODUCTS} P
      ON A.productId = P.id
    JOIN ${DatabaseHelper.T_UNIT_CONVERSIONS} C
      ON C.fromUomId = A.uomId
     AND C.toUomId   = P.baseUomId
    WHERE A.productId = ?
  """.trimIndent()

        return try {
            db.rawQuery(sql, arrayOf(productId.toString())).use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getDouble(cursor.getColumnIndexOrThrow("baseUnitPrice"))
                } else {
                    0.0
                }
            }
        } catch (ex: Exception) {
            android.util.Log.e(
                "DatabaseHelper",
                "Error computing baseUnitPrice for productId=$productId",
                ex
            )
            0.0
        }
    }

    fun recalibrateStock(productId: Int) {
        val level = gatherNetQuantity(productId)
        val price = calculateBaseUnitPrice(level.productId)
        val db = this.db
        val cv = ContentValues().apply {
            put("productId", level.productId)
            put("quantity", level.netQuantity)
            put("unitPrice", price)
            put("uomId", level.baseUomId)
            put("lastUpdated", helper.nowIso())
        }

        val existingId = db.rawQuery(
            "SELECT id FROM ${DatabaseHelper.T_STOCK} WHERE productId = ? AND uomId = ?",
            arrayOf(level.productId.toString(), level.baseUomId.toString())
        ).use { rc -> if (rc.moveToFirst()) rc.getLong(0) else 0L }

        if (existingId > 0) {
            db.update(DatabaseHelper.T_STOCK, cv, "id = ?", arrayOf(existingId.toString()))
        } else {
            db.insert(DatabaseHelper.T_STOCK, null, cv)
        }
    }

    fun recalibrateStock_delete(): String {
        val db = this.db
        return try {
            val transactionCursor = db.rawQuery(
                """
                SELECT productId, transactionType, quantity, uomId, transactionDate
                FROM ${DatabaseHelper.T_TRANSACTIONS}
                ORDER BY transactionDate ASC
            """, null
            )
            val stockMap =
                mutableMapOf<Int, Pair<Double, Int>>()

            transactionCursor.use { cursor ->
                while (cursor.moveToNext()) {
                    val productId = cursor.getInt(cursor.getColumnIndexOrThrow("productId"))
                    val transactionType =
                        cursor.getString(cursor.getColumnIndexOrThrow("transactionType"))
                    val quantity = cursor.getDouble(cursor.getColumnIndexOrThrow("quantity"))
                    val uomId = cursor.getInt(cursor.getColumnIndexOrThrow("uomId"))

                    val baseUnitCursor = db.rawQuery(
                        "SELECT baseUomId FROM ${DatabaseHelper.T_PRODUCTS} WHERE id = ?",
                        arrayOf(productId.toString())
                    )
                    val baseUnitId = baseUnitCursor.use { bc ->
                        if (bc.moveToFirst()) bc.getInt(0) else uomId
                    }

                    val convCursor = db.rawQuery(
                        "SELECT conversionFactor FROM ${DatabaseHelper.T_UNIT_CONVERSIONS} WHERE fromUomId = ? AND toUomId = ?",
                        arrayOf(uomId.toString(), baseUnitId.toString())
                    )
                    val conversionFactor = convCursor.use { cc ->
                        if (cc.moveToFirst()) cc.getDouble(0) else 1.0
                    }

                    val baseQuantity = quantity * conversionFactor
                    val netQuantity = stockMap[productId]?.first ?: 0.0

                    stockMap[productId] = when (transactionType) {
                        "in" -> Pair(netQuantity + baseQuantity, baseUnitId)
                        "out" -> Pair(netQuantity - baseQuantity, baseUnitId)
                        else -> stockMap[productId] ?: Pair(0.0, baseUnitId)
                    }
                }
            }

            val updatedIds = mutableListOf<Int>()
            stockMap.forEach { (productId, pair) ->
                val (netQuantity, baseUnitId) = pair
                val cv = ContentValues().apply {
                    put("productId", productId)
                    put("quantity", netQuantity)
                    put("uomId", baseUnitId)
                    put("lastUpdated", helper.nowIso())
                }
                val existingId = db.rawQuery(
                    "SELECT id FROM ${DatabaseHelper.T_STOCK} WHERE productId = ? AND uomId = ?",
                    arrayOf(productId.toString(), baseUnitId.toString())
                ).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(0) else 0
                }
                if (existingId > 0) {
                    db.update(DatabaseHelper.T_STOCK, cv, "id = ?", arrayOf(existingId.toString()))
                    updatedIds.add(existingId)
                } else {
                    val newId = db.insert(DatabaseHelper.T_STOCK, null, cv)
                    updatedIds.add(newId.toInt())
                }
            }

            JSONObject().put("status", "success").put("updatedIds", JSONArray(updatedIds))
                .toString()
        } catch (e: Exception) {
            helper.logError("DatabaseHelper", "Failed to recalibrate stock", e.stackTraceToString())
            JSONObject().put("status", "error").put("message", e.message).toString()
        }
    }

    fun gatherNetQuantity(productId: Int): StockLevel {
        val db = this.db

        val baseUomId = db.rawQuery(
            "SELECT baseUomId FROM ${DatabaseHelper.T_PRODUCTS} WHERE id = ?",
            arrayOf(productId.toString())
        ).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

        var netQty = 0.0
        db.rawQuery(
            """
      SELECT transactionType, quantity, uomId
        FROM ${DatabaseHelper.T_TRANSACTIONS}
       WHERE productId = ?
       ORDER BY transactionDate ASC
    """.trimIndent(),
            arrayOf(productId.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val type = cursor.getString(cursor.getColumnIndexOrThrow("transactionType"))
                val qty = cursor.getDouble(cursor.getColumnIndexOrThrow("quantity"))
                val uomId = cursor.getInt(cursor.getColumnIndexOrThrow("uomId"))

                val factor = db.rawQuery(
                    """
          SELECT conversionFactor
            FROM ${DatabaseHelper.T_UNIT_CONVERSIONS}
           WHERE fromUomId = ? AND toUomId = ?
        """.trimIndent(),
                    arrayOf(uomId.toString(), baseUomId.toString())
                ).use { cc ->
                    if (cc.moveToFirst()) cc.getDouble(0) else 1.0
                }

                val baseQty = qty * factor
                netQty = if (type == "in") netQty + baseQty else netQty - baseQty
            }
        }

        return StockLevel(
            productId = productId,
            netQuantity = netQty,
            baseUomId = baseUomId
        )
    }

    fun getRawStockSummary(): String {
        return try {
            val sql = """
            SELECT
                p.id AS id,
                p.name AS product,
                IFNULL(s.quantity, 0) AS quantity,
                u.name AS unit
            FROM products p
            LEFT JOIN stock s ON p.id = s.productId
            LEFT JOIN uoms u ON u.id = s.uomId
            LEFT JOIN categories c ON c.id = p.categoryId
            WHERE c.name = 'Raw'
            ORDER BY p.name
        """.trimIndent()

            val result = helper.fetchAll(rdb.rawQuery(sql, null))

            JSONObject().put("stock", result).toString()

        } catch (ex: Exception) {
            android.util.Log.e("DB", "getRawStockSummary failed", ex)
            JSONObject().put("error", ex.message ?: "getRawStockSummary failed").toString()
        }
    }

    fun getStockSummary(): String =
        helper.fetchAll(
            rdb.rawQuery(
                """
            SELECT p.id AS productId, p.name AS productName, s.quantity, u.name AS unitName, strftime('%m/%d/%Y', s.lastUpdated) lastUpdated, s.unitPrice, p.lowStockThreshold
            FROM stock s
            JOIN products p ON s.productId = p.id
            JOIN uoms u ON p.baseUomId = u.id
        """, null
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
        """, null
            )
        ).toString()

    fun saveConsumption(json: String): String {
        return try {
            val obj = JSONObject(json)
            val productId = obj.getInt("productId")
            val txnType = obj.optString("transactionType", "out")
            val quantity = obj.getDouble("quantity")
            var unitPrice = obj.optDouble("unitPrice", 0.0)
            unitPrice = calculateBaseUnitPrice(productId)

            val unitId = obj.getInt("unitId")
            val notes = obj.optString("notes", "")
            val txnDate = obj.optString("timestamp", helper.nowIso())
            val amountUsed = quantity * unitPrice

            val batchId = helper.getBatchId()
            val rowId = helper.insertTransaction(
                refType = "FeedConsumed",
                refId = batchId,
                productId = productId,
                lineId = 1,
                txnType = txnType,
                quantity = quantity,
                uomId = unitId,
                txnDate = txnDate,
                notes = notes
            )

            val consumeInput = AccountingTransactionInput(
                type = "FeedUse",
                subType = null,
                table = "ProductionBatches",
                refId = batchId,
                refId2 = 0,
                productId = productId,
                amount = unitPrice * quantity,
                unitPrice = unitPrice,
                date = null,
                notes = null
            )

            helper.insertAccountingTransactionAndPostJournal(consumeInput)

            recalibrateStock(productId)

            val baseQty = run {
                val baseUomId = db.rawQuery(
                    "SELECT baseUomId FROM ${DatabaseHelper.T_PRODUCTS} WHERE id = ?",
                    arrayOf(productId.toString())
                ).use { c -> if (c.moveToFirst()) c.getInt(0) else unitId }

                val factor = db.rawQuery(
                    "SELECT conversionFactor FROM ${DatabaseHelper.T_UNIT_CONVERSIONS} WHERE fromUomId = ? AND toUomId = ?",
                    arrayOf(unitId.toString(), baseUomId.toString())
                ).use { c -> if (c.moveToFirst()) c.getDouble(0) else 1.0 }

                quantity * factor
            }

            JSONObject().put("id", rowId).toString()

        } catch (ex: Exception) {
            android.util.Log.e("DatabaseHelper", "saveConsumption failed", ex)
            JSONObject().put("error", ex.message ?: "Unknown error").toString()
        }
    }
}
