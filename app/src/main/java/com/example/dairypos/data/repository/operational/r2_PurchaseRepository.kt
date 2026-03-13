package com.example.dairypos.data.repository.operational

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.example.dairypos.DatabaseHelper
import com.example.dairypos.data.repository.contracts.IAccountingEngine
import com.example.dairypos.data.repository.contracts.OperationalEvent
import com.example.dairypos.data.repository.erp.forEach
import com.example.dairypos.data.repository.erp.singleInt
import com.example.dairypos.model.*
import org.json.JSONArray
import org.json.JSONObject

/** Operational — purchase recording and stock receiving. Triggers Purchase journal entries. */
class r2_PurchaseRepository(
    private val helper: DatabaseHelper,
    private val engine: IAccountingEngine
) {
    private val db  get() = helper.writableDatabase
    private val rdb get() = helper.readableDatabase

    fun savePurchase(json: String): String {
        return try {
            val purchaseReceivedId = rdb.singleInt(
                "SELECT id FROM ${DatabaseHelper.T_PURCHASE_STATUS} WHERE name = ?",
                arrayOf("Received")
            )
            val obj = JSONObject(json)
            val id = obj.optInt("id", 0)
            val cv = ContentValues().apply {
                put("supplierId", obj.getInt("supplierId"))
                put("purchaseDate", obj.getString("purchaseDate"))
                put("statusId", purchaseReceivedId)
                put("notes", obj.optString("notes", ""))
                put("updateDate", obj.optString("updateDate", obj.optString("createDate", helper.nowIso())))
                put("updatedOn", System.currentTimeMillis())
            }
            val purchaseId = db.use { db ->
                if (id > 0) {
                    db.update(DatabaseHelper.T_PURCHASES, cv, "id=?", arrayOf(id.toString()))
                    id
                } else {
                    cv.put("createDate", obj.optString("createDate", helper.nowIso()))
                    db.insert(DatabaseHelper.T_PURCHASES, null, cv).toInt()
                }
            }
            val defaultStatusId = helper.getStatusId("purchaseItems", "Received")
            db.use { db ->
                val items = obj.getJSONArray("items")
                for (i in 0 until items.length()) {
                    val it = items.getJSONObject(i)
                    val lineId = it.optInt("lineId", 0)
                    var statusId = it.optInt("statusId", 0)
                    val lineAmount = it.optDouble("lineAmount", 0.00)
                    if (statusId <= 0) statusId = defaultStatusId
                    val cvItem = ContentValues().apply {
                        put("purchaseId", purchaseId)
                        put("productId", it.getInt("productId"))
                        put("supplierItemId", it.getInt("supplierItemId"))
                        put("uomId", it.getInt("uomId"))
                        put("statusId", statusId)
                        put("price", it.getDouble("pricePerUom"))
                        put("quantity", it.getDouble("quantity"))
                        put("amount", lineAmount)
                        put("updatedOn", System.currentTimeMillis())
                    }
                    if (lineId > 0) {
                        val updated = db.update(DatabaseHelper.T_PURCHASE_ITEMS, cvItem, "id=?", arrayOf(lineId.toString()))
                        if (updated == 0) db.insert(DatabaseHelper.T_PURCHASE_ITEMS, null, cvItem)
                    } else {
                        db.insert(DatabaseHelper.T_PURCHASE_ITEMS, null, cvItem)
                    }
                }
            }
            receiveStock()
            JSONObject().put("id", purchaseId).toString()
        } catch (e: Exception) {
            Log.e("r2Purchase", "savePurchase failed", e)
            JSONObject().put("error", e.message ?: "savePurchase execution failed").toString()
        }
    }

    private fun SQLiteDatabase.getReceivedPurchaseLines(purchaseId: Int, lineReceivedId: Int): List<ReceivedPurchaseLine> {
        val list = mutableListOf<ReceivedPurchaseLine>()
        rdb.forEach(
            """
            SELECT a.id AS lineId, productId, p.name as productName, c.name AS category, supplierItemId, quantity, uomId, amount
              FROM ${DatabaseHelper.T_PURCHASE_ITEMS} a
              JOIN ${DatabaseHelper.T_PRODUCTS} p ON a.productId = p.id
              JOIN ${DatabaseHelper.T_CATEGORY} c ON p.categoryId = c.id
             WHERE a.purchaseId = ? AND a.statusId = ?
            """.trimIndent(),
            arrayOf(purchaseId.toString(), lineReceivedId.toString())
        ) { cursor ->
            list += ReceivedPurchaseLine(
                purchaseId = purchaseId,
                lineId = cursor.getInt(0),
                productId = cursor.getInt(1),
                productName = cursor.getString(2),
                category = cursor.getString(3),
                supplierItemId = cursor.getInt(4),
                quantity = cursor.getDouble(5),
                uomId = cursor.getInt(6),
                amount = cursor.getDouble(7)
            )
        }
        return list
    }

    fun receiveStock(): String {
        return try {
            val purchaseReceivedId = rdb.singleInt("SELECT id FROM ${DatabaseHelper.T_PURCHASE_STATUS} WHERE name = ?", arrayOf("Received"))
            val purchaseStockedId  = rdb.singleInt("SELECT id FROM ${DatabaseHelper.T_PURCHASE_STATUS} WHERE name = ?", arrayOf("Stocked"))
            val lineReceivedId     = rdb.singleInt("SELECT id FROM ${DatabaseHelper.T_PURCHASE_LINE_STATUS} WHERE name = ?", arrayOf("Received"))
            val lineStockedId      = rdb.singleInt("SELECT id FROM ${DatabaseHelper.T_PURCHASE_LINE_STATUS} WHERE name = ?", arrayOf("Stocked"))

            val toProcess = mutableListOf<Int>()
            rdb.forEach("SELECT id FROM ${DatabaseHelper.T_PURCHASES} WHERE statusId = ?", arrayOf(purchaseReceivedId.toString())) { c ->
                toProcess += c.getInt(0)
            }

            toProcess.forEach { purchaseId ->
                val receivedLines = rdb.getReceivedPurchaseLines(purchaseId, lineReceivedId)
                receivedLines.forEach { line ->
                    val transJson = JSONObject().apply {
                        put("referenceType", "Purchase")
                        put("referenceId", line.purchaseId)
                        put("lineId", line.lineId)
                        put("productId", line.productId)
                        put("transactionType", "in")
                        put("quantity", line.quantity)
                        put("uomId", line.uomId)
                        put("notes", JSONObject.NULL)
                    }.toString()
                    helper.saveTransaction(transJson)

                    engine.processEvent(OperationalEvent(
                        transactionTypeName = "Purchase",
                        subType             = line.category,
                        tableName           = DatabaseHelper.T_PURCHASE_ITEMS,
                        entityId            = line.lineId,
                        amount              = line.amount,
                        productId           = line.productId
                    ))

                    helper.executeNonQuery("UPDATE ${DatabaseHelper.T_PURCHASE_ITEMS} SET statusId = $lineStockedId, updatedOn = ${System.currentTimeMillis()} WHERE id = ${line.lineId}")
                    helper.recalibrateStock(line.productId)
                }
                helper.executeNonQuery("UPDATE ${DatabaseHelper.T_PURCHASES} SET statusId = $purchaseStockedId, updatedOn = ${System.currentTimeMillis()} WHERE id = $purchaseId")
            }

            JSONObject().put("processedPurchases", toProcess.size).toString()
        } catch (e: Exception) {
            Log.e("r2Purchase", "receiveStock failed", e)
            JSONObject().put("error", e.message ?: "receiveStock execution failed").toString()
        }
    }

    fun deletePurchase(id: Int): String {
        val now = System.currentTimeMillis()
        val db = this.db
        db.beginTransaction()
        try {
            val cvItems = ContentValues().apply { put("isDeleted", 1); put("updatedOn", now) }
            db.update(DatabaseHelper.T_PURCHASE_ITEMS, cvItems, "purchaseId=?", arrayOf(id.toString()))
            val cvPurchase = ContentValues().apply { put("isDeleted", 1); put("updatedOn", now) }
            val count = db.update(DatabaseHelper.T_PURCHASES, cvPurchase, "id=?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
            return JSONObject().put("deleted", count).toString()
        } finally {
            db.endTransaction()
        }
    }

    fun saveAssetPurchase(json: String): String {
        return try {
            val obj = JSONObject(json)
            val supplierId  = obj.getInt("supplierId")
            val date        = obj.getString("purchaseDate")
            val itemsArray  = obj.getJSONArray("items")

            val cvHdr = ContentValues().apply {
                put("supplierId",  supplierId)
                put("purchaseDate", date)
                put("statusId",    helper.getStatusId("purchaseStatus", "Stocked"))
                put("createDate",  helper.nowIso())
                put("updateDate",  helper.nowIso())
                put("updatedOn",   System.currentTimeMillis())
            }
            val purchaseId = db.use { db -> db.insert(DatabaseHelper.T_PURCHASES, null, cvHdr).toInt() }

            for (i in 0 until itemsArray.length()) {
                val lineObj    = itemsArray.getJSONObject(i)
                val productId  = lineObj.getInt("productId")
                val uomId      = lineObj.getInt("uomId")
                val quantity   = lineObj.getDouble("quantity")
                val pricePerUom = lineObj.getDouble("pricePerUom")

                val cvLine = ContentValues().apply {
                    put("purchaseId", purchaseId)
                    put("productId",  productId)
                    put("uomId",      uomId)
                    put("statusId",   helper.getStatusId("purchaseItems", "Stocked"))
                    put("price",      pricePerUom)
                    put("quantity",   quantity)
                    put("updatedOn",  System.currentTimeMillis())
                }
                val lineId    = db.insert(DatabaseHelper.T_PURCHASE_ITEMS, null, cvLine).toInt()
                val baseUomId = helper.getBaseUomId(productId)
                val factor    = helper.getConversionFactor(uomId, baseUomId)
                helper.insertTransaction(
                    refType   = "PurchaseAsset",
                    refId     = purchaseId,
                    productId = productId,
                    lineId    = lineId,
                    txnType   = "in",
                    quantity  = quantity * factor,
                    uomId     = baseUomId,
                    txnDate   = helper.nowIso(),
                    notes     = null
                )
            }

            val totalAmt = (0 until itemsArray.length()).sumOf { idx ->
                val it = itemsArray.getJSONObject(idx)
                it.getDouble("quantity") * it.getDouble("pricePerUom")
            }

            val invAcct = helper.getAccountIdByCode("1001")
            val payAcct = helper.getAccountIdByCode("2000")
            helper.recordJournalEntry(
                refType  = "PurchaseAsset",
                refId    = purchaseId,
                debitId  = invAcct,
                creditId = payAcct,
                amount   = totalAmt,
                desc     = "Purchased Milk Inventory #$purchaseId"
            )

            JSONObject().put("purchaseId", purchaseId).toString()
        } catch (ex: Exception) {
            Log.e("r2Purchase", "saveAssetPurchase failed", ex)
            JSONObject().put("error", ex.message ?: "Unknown error").toString()
        }
    }

    fun getPurchase(id: String): String =
        helper.fetchAll(rdb.rawQuery("SELECT * FROM ${DatabaseHelper.T_PURCHASES} WHERE id = ?", arrayOf(id))).toString()

    fun getPurchase(purchaseId: Int): String? {
        val cursor: Cursor = rdb.query(
            DatabaseHelper.T_PURCHASES,
            arrayOf("id", "supplierId", "purchaseDate", "statusId", "notes"),
            "id = ?", arrayOf(purchaseId.toString()), null, null, null
        )
        return cursor.use {
            if (!it.moveToFirst()) return null
            JSONObject().apply {
                put("id",           it.getInt(it.getColumnIndexOrThrow("id")))
                put("supplierId",   it.getInt(it.getColumnIndexOrThrow("supplierId")))
                put("purchaseDate", it.getString(it.getColumnIndexOrThrow("purchaseDate")))
                put("statusId",     it.getInt(it.getColumnIndexOrThrow("statusId")))
                put("notes",        it.getString(it.getColumnIndexOrThrow("notes")) ?: "")
            }.toString()
        }
    }

    fun getPurchaseById(id: Int): PurchaseHeader? {
        val sql = """
            SELECT id, supplierId, purchaseDate, statusId, notes, createDate, updateDate
              FROM ${DatabaseHelper.T_PURCHASES}
             WHERE id = ?
        """.trimIndent()
        rdb.rawQuery(sql, arrayOf(id.toString())).use { c ->
            if (!c.moveToFirst()) return null
            return PurchaseHeader(
                id = c.getInt(0), supplierId = c.getInt(1), purchaseDate = c.getString(2),
                statusId = c.getInt(3), notes = c.getString(4) ?: "",
                createDate = c.getString(5), updateDate = c.getString(6)
            )
        }
    }

    fun getPurchaseItems(purchaseId: String): String =
        helper.fetchAll(rdb.rawQuery("SELECT * FROM ${DatabaseHelper.T_PURCHASE_ITEMS} WHERE purchaseId = ?", arrayOf(purchaseId))).toString()

    fun getPurchaseItems(purchaseId: Int): String {
        val cursor: Cursor = rdb.query(
            DatabaseHelper.T_PURCHASE_ITEMS,
            arrayOf("id", "purchaseId", "supplierItemId", "statusId", "price", "quantity"),
            "purchaseId = ?", arrayOf(purchaseId.toString()), null, null, "id ASC"
        )
        val arr = JSONArray()
        cursor.use {
            while (it.moveToNext()) {
                arr.put(JSONObject().apply {
                    put("id",            it.getInt(it.getColumnIndexOrThrow("id")))
                    put("purchaseId",    it.getInt(it.getColumnIndexOrThrow("purchaseId")))
                    put("supplierItemId",it.getInt(it.getColumnIndexOrThrow("supplierItemId")))
                    put("statusId",      it.getInt(it.getColumnIndexOrThrow("statusId")))
                    put("pricePerUom",   it.getDouble(it.getColumnIndexOrThrow("price")))
                    put("quantity",      it.getDouble(it.getColumnIndexOrThrow("quantity")))
                })
            }
        }
        return arr.toString()
    }

    fun getPurchaseItemsByPurchaseId(purchaseId: Int): List<PurchaseItem1> {
        val out = mutableListOf<PurchaseItem1>()
        val sql = """
            SELECT id, purchaseId, supplierItemId, quantity, uomId, pricePerUom
              FROM ${DatabaseHelper.T_PURCHASE_ITEMS}
             WHERE purchaseId = ?
        """.trimIndent()
        rdb.rawQuery(sql, arrayOf(purchaseId.toString())).use { c ->
            while (c.moveToNext()) {
                out.add(PurchaseItem1(
                    id = c.getInt(0), purchaseId = c.getInt(1), supplierItemId = c.getInt(2),
                    quantity = c.getDouble(3), uomId = c.getInt(4), pricePerUom = c.getDouble(5)
                ))
            }
        }
        return out
    }

    fun getPurchaseStatus(): String =
        helper.fetchAll(rdb.rawQuery("SELECT * FROM ${DatabaseHelper.T_PURCHASE_STATUS}", null)).toString()

    fun getPurchases(): String =
        helper.fetchAll(rdb.rawQuery("SELECT * FROM ${DatabaseHelper.T_PURCHASES} WHERE isDeleted=0", null)).toString()

    fun queryPurchaseByDateReport(start: String, end: String): String {
        val sql = """
            SELECT
                sup.name        AS supplier,
                pro.name        AS productName,
                u.name          AS unitName,
                t.quantity      AS quantity,
                pur.id          AS purchaseId,
                pi.id           AS lineId,
                (t.quantity * pi.price) AS lineAmount,
                pls.name        AS purchaseEvent,
                pur.purchaseDate AS purchaseDate,
                pro.name        AS badgeName,
                (t.quantity * pi.price) AS badgeValues
              FROM transactions t
              JOIN products p          ON t.productId      = p.id
              JOIN uoms     u          ON t.uomId          = u.id
              LEFT JOIN categories c   ON p.categoryId     = c.id
              JOIN purchases pur       ON t.referenceId    = pur.id
                                     AND t.referenceType   = 'Purchase'
                                     AND t.transactionType = 'in'
              JOIN suppliers sup       ON pur.supplierId   = sup.id
              JOIN purchaseItems pi    ON pur.id           = pi.purchaseId
                                     AND pi.id             = t.lineId
              JOIN products pro        ON pi.productId     = pro.id
              JOIN purchaseLineStatus pls ON pi.statusId   = pls.id
             WHERE date(pur.purchaseDate) BETWEEN ? AND ?
             ORDER BY pur.purchaseDate DESC, pur.id, pi.id
        """.trimIndent()
        return helper.fetchAll(rdb.rawQuery(sql, arrayOf(start, end))).toString()
    }

    fun getSupplierPurchaseSummariesThisMonth(): String {
        val since = helper.firstOfMonthIso()
        val sql = """
            SELECT
                pr.supplierId,
                COUNT(DISTINCT pr.id)                              AS purchaseCount,
                SUM(pi.quantity * IFNULL(uc.conversionFactor, 1)) AS qtyPurchased,
                SUM(pi.price * pi.quantity)                        AS totalPrice
              FROM ${DatabaseHelper.T_PURCHASE_ITEMS} pi
              JOIN ${DatabaseHelper.T_PURCHASES} pr         ON pi.purchaseId  = pr.id
              JOIN ${DatabaseHelper.T_SUPPLIER_ITEMS} si    ON pi.supplierItemId = si.id
              LEFT JOIN ${DatabaseHelper.T_PRODUCTS} p      ON si.productId   = p.id
              LEFT JOIN ${DatabaseHelper.T_UNIT_CONVERSIONS} uc ON uc.fromUomId = pi.uomId AND uc.toUomId = p.baseUomId
             WHERE pr.purchaseDate >= ?
             GROUP BY pr.supplierId
             ORDER BY pr.supplierId
        """.trimIndent()
        val arr = JSONArray()
        rdb.rawQuery(sql, arrayOf(since)).use { c ->
            while (c.moveToNext()) {
                arr.put(JSONObject().apply {
                    put("supplierId",    c.getInt(0))
                    put("purchaseCount", c.getInt(1))
                    put("qtyPurchased",  c.getDouble(2))
                    put("totalPrice",    c.getDouble(3))
                })
            }
        }
        return arr.toString()
    }

    fun getAveragePurchasedMilkCogs(productId: Int): String {
        val invAcct = helper.getAccountIdByCode("1001")
        val purchasedCost = rdb.rawQuery(
            "SELECT COALESCE(SUM(amount),0) FROM ${DatabaseHelper.T_JOURNAL} WHERE referenceType='PurchaseAsset' AND debitAccountId=?",
            arrayOf(invAcct.toString())
        ).use { c -> if (c.moveToFirst()) c.getDouble(0) else 0.0 }
        val purchasedQty = rdb.rawQuery(
            "SELECT COALESCE(SUM(quantity),0) FROM ${DatabaseHelper.T_TRANSACTIONS} WHERE referenceType='PurchaseAsset' AND productId=?",
            arrayOf(productId.toString())
        ).use { c -> if (c.moveToFirst()) c.getDouble(0) else 0.0 }
        val avg = if (purchasedQty > 0) purchasedCost / purchasedQty else 0.0
        return JSONObject().put("purchasedAvg", avg).toString()
    }
}
