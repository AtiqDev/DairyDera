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

class ProcurementRepository(private val helper: DatabaseHelper) {
    private val db get() = helper.writableDatabase
    private val rdb get() = helper.readableDatabase

    fun getSupplierId(db: SQLiteDatabase, name: String): Long? = helper.run {
        val projection = arrayOf("id")
        val selection = "name = ?"
        val selectionArgs = arrayOf(name)
        db.query("suppliers", projection, selection, selectionArgs, null, null, null).use { cursor ->
            return if (cursor.moveToFirst()) {
                cursor.getLong(cursor.getColumnIndexOrThrow("id"))
            } else {
                null
            }
        }
    }

    fun getSuppliers(): String =
        helper.fetchAll(rdb.rawQuery("SELECT * FROM ${DatabaseHelper.T_SUPPLIERS} ORDER BY name", null)).toString()

    fun getSuppliersSearch(term: String): String {
        val sql = "SELECT * FROM ${DatabaseHelper.T_SUPPLIERS} WHERE name LIKE ? OR address LIKE ? OR phone LIKE ? ORDER BY name"
        val pattern = "%$term%"
        return helper.fetchAll(rdb.rawQuery(sql, arrayOf(pattern, pattern, pattern))).toString()
    }

    fun deleteSupplier(id: Int): String {
        val count = db.delete(DatabaseHelper.T_SUPPLIERS, "id=?", arrayOf(id.toString()))
        return JSONObject().put("deleted", count).toString()
    }

    fun deletePurchase(id: Int): String {
        val db = this.db
        db.beginTransaction()
        try {
            db.delete(DatabaseHelper.T_PURCHASE_ITEMS, "purchaseId=?", arrayOf(id.toString()))
            val count = db.delete(DatabaseHelper.T_PURCHASES, "id=?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
            return JSONObject().put("deleted", count).toString()
        } finally {
            db.endTransaction()
        }
    }

    fun getSupplierItems(supplierId: Int): String {
        return try {
            val db = rdb
            val cursor = db.rawQuery(
                """
            SELECT
              si.id,
              si.supplierId,
              si.sku,
              p.name AS itemName,
              si.isDefault,
              si.pricePerUom,
              si.uomId,
              si.productId,
              u.name AS uomName
            FROM ${DatabaseHelper.T_SUPPLIER_ITEMS} si
            JOIN ${DatabaseHelper.T_UOMS} u ON si.uomId = u.id
            JOIN ${DatabaseHelper.T_PRODUCTS} p ON si.productId = p.id
            WHERE si.supplierId = ?
            """.trimIndent(),
                arrayOf(supplierId.toString())
            )

            val jsonArray = helper.fetchAll(cursor)
            cursor.close()

            if (jsonArray.length() > 0) {
                var hasDefault = false
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    if (obj.optInt("isDefault", 0) == 1) {
                        hasDefault = true
                        break
                    }
                }

                if (!hasDefault) {
                    jsonArray.getJSONObject(0).put("isDefault", 1)
                }
            }

            jsonArray.toString()
        } catch (e: Exception) {
            Log.e("DatabaseHelper", "❌ getSupplierItems error", e)
            "[]"
        }
    }

    fun saveSupplierItems(json: String): String {
        val obj = JSONObject(json)
        val supplierId = obj.getInt("supplierId")
        val items = obj.getJSONArray("items")

        db.use { db ->
            db.delete(DatabaseHelper.T_SUPPLIER_ITEMS, "supplierId=?", arrayOf(supplierId.toString()))

            for (i in 0 until items.length()) {
                val it = items.getJSONObject(i)
                val productId = it.getInt("productId")

                val cursor = db.rawQuery(
                    "SELECT name FROM ${DatabaseHelper.T_PRODUCTS} WHERE id = ?",
                    arrayOf(productId.toString())
                )
                val productName = helper.fetchScalarString(
                    "SELECT name FROM ${DatabaseHelper.T_PRODUCTS} WHERE id = ?",
                    arrayOf(productId.toString())
                )

                val itemName = if (cursor.moveToFirst()) {
                    cursor.getString(0)
                } else {
                    ""
                }
                cursor.close()

                val cv = ContentValues().apply {
                    put("supplierId", supplierId)
                    put("sku", it.optString("sku", ""))
                    put("itemName", itemName)
                    put("pricePerUom", it.optDouble("pricePerUom", 0.0))
                    put("uomId", it.optInt("uomId", 0))
                    put("productId", productId)
                }

                db.insert(DatabaseHelper.T_SUPPLIER_ITEMS, null, cv)
            }
        }

        return JSONObject().put("count", items.length()).toString()
    }

    fun saveSupplier(json: String): String {
        val obj = JSONObject(json)
        val id = obj.optInt("id", 0)
        val cv = ContentValues().apply {
            put("name", obj.getString("name"))
            put("address", obj.optString("address", ""))
            put("phone", obj.optString("phone", ""))
            put("rate", obj.optInt("rate"))
            put("quantity", obj.optDouble("quantity"))
            put("updateDate", obj.optString("updateDate", obj.optString("createDate", "")))
        }
        val newId = db.use { db ->
            if (id > 0) {
                db.update(DatabaseHelper.T_SUPPLIERS, cv, "id=?", arrayOf(id.toString()))
                id
            } else {
                cv.put("createDate", obj.optString("createDate", helper.nowIso()))
                db.insert(DatabaseHelper.T_SUPPLIERS, null, cv).toInt()
            }
        }
        return JSONObject().put("id", newId).toString()
    }

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
                put(
                    "updateDate",
                    obj.optString("updateDate", obj.optString("createDate", helper.nowIso()))
                )
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
            val defaultStatusId = helper.getStatusId("purchaseItems","Received")
            db.use { db ->
                val items = obj.getJSONArray("items")

                for (i in 0 until items.length()) {
                    val it = items.getJSONObject(i)
                    val lineId = it.optInt("lineId", 0)
                    var statusId = it.optInt("statusId", 0)
                    val lineAmount = it.optDouble("lineAmount", 0.00)
                    if (statusId <= 0) {
                        statusId = defaultStatusId
                    }

                    val cvItem = ContentValues().apply {
                        put("purchaseId", purchaseId)
                        put("productId", it.getInt("productId"))
                        put("supplierItemId", it.getInt("supplierItemId"))
                        put("uomId", it.getInt("uomId"))
                        put("statusId", statusId)
                        put("price", it.getDouble("pricePerUom"))
                        put("quantity", it.getDouble("quantity"))
                        put("amount", lineAmount)
                    }

                    if (lineId > 0) {
                        val updated = db.update(
                            DatabaseHelper.T_PURCHASE_ITEMS,
                            cvItem,
                            "id=?",
                            arrayOf(lineId.toString())
                        )
                        if (updated == 0) {
                            db.insert(DatabaseHelper.T_PURCHASE_ITEMS, null, cvItem)
                        }
                    } else {
                        db.insert(DatabaseHelper.T_PURCHASE_ITEMS, null, cvItem)
                    }
                }
            }

            receiveStock()
            JSONObject().put("id", purchaseId).toString()

        } catch (e: Exception) {
            android.util.Log.e("DatabaseHelper", "savePurchase failed", e)
            JSONObject()
                .put("error", e.message ?: "savePurchase execution failed")
                .toString()
        }
    }

    private fun SQLiteDatabase.getReceivedPurchaseLines(
        purchaseId: Int,
        lineReceivedId: Int
    ): List<ReceivedPurchaseLine> {
        val list = mutableListOf<ReceivedPurchaseLine>()
        helper.run {
            rdb.forEach(
                """
          SELECT a.id AS lineId, productId, p.name as productName, c.name AS category, supplierItemId, quantity, uomId, amount
          FROM ${DatabaseHelper.T_PURCHASE_ITEMS} a join ${DatabaseHelper.T_PRODUCTS} p on a.productId = p.id join ${DatabaseHelper.T_CATEGORY} c on p.categoryId = c.id
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
        }
        return list
    }

    fun receiveStock(): String {
        return try {

            val purchaseReceivedId = rdb.singleInt(
                "SELECT id FROM ${DatabaseHelper.T_PURCHASE_STATUS} WHERE name = ?",
                arrayOf("Received")
            )
            val purchaseStockedId = rdb.singleInt(
                "SELECT id FROM ${DatabaseHelper.T_PURCHASE_STATUS} WHERE name = ?",
                arrayOf("Stocked")
            )
            val lineReceivedId = rdb.singleInt(
                "SELECT id FROM ${DatabaseHelper.T_PURCHASE_LINE_STATUS} WHERE name = ?",
                arrayOf("Received")
            )
            val lineStockedId = rdb.singleInt(
                "SELECT id FROM ${DatabaseHelper.T_PURCHASE_LINE_STATUS} WHERE name = ?",
                arrayOf("Stocked")
            )

            val toProcess = mutableListOf<Int>()
            rdb.forEach(
                "SELECT id FROM ${DatabaseHelper.T_PURCHASES} WHERE statusId = ?",
                arrayOf(purchaseReceivedId.toString())
            ) { c ->
                toProcess += c.getInt(0)
            }

            toProcess.forEach { purchaseId ->
                val receivedLines =
                    rdb.getReceivedPurchaseLines(purchaseId, lineReceivedId)

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

                    val purchaseInput = AccountingTransactionInput(
                        type = "Purchase",
                        subType = line.category,
                        table = DatabaseHelper.T_PURCHASE_ITEMS,
                        refId = line.lineId,
                        refId2 = 0,
                        productId = line.productId,
                        amount = line.amount,
                        unitPrice = 0.00,
                        date = null,
                        notes = null
                    )

                    val txnId = helper.insertAccountingTransactionAndPostJournal(purchaseInput)

                    val sql1 =
                        "UPDATE ${DatabaseHelper.T_PURCHASE_ITEMS} SET statusId = $lineStockedId WHERE id = ${line.lineId}"
                    helper.executeNonQuery(sql1)

                    helper.recalibrateStock(line.productId)
                }
                val sql2 =
                    "UPDATE ${DatabaseHelper.T_PURCHASES} SET statusId = $purchaseStockedId WHERE id = $purchaseId"
                helper.executeNonQuery(sql2)
            }

            JSONObject().put("processedPurchases", toProcess.size).toString()

        } catch (e: Exception) {
            android.util.Log.e("DatabaseHelper", "receiveStock failed", e)
            JSONObject().put("error", e.message ?: "receiveStock execution failed").toString()
        }
    }

    fun getApPaymentMethods(): String =
        helper.fetchAll(rdb.rawQuery("SELECT * FROM apPaymentMethods ORDER BY id", null)).toString()

    fun getSuppliersWithOpenBalance(): String {
        val payablesAcct = helper.getAccountIdByCode("2000")
        val sql = """
            SELECT
              s.id,
              s.name,
              COALESCE(created.total, 0) - COALESCE(paid.total, 0) AS openBalance
            FROM suppliers s
            LEFT JOIN (
              SELECT p.supplierId, SUM(je.amount) AS total
              FROM journalEntries je
              JOIN accountingTransaction at ON je.referenceId = at.id
                                           AND at.transactionType = 'Purchase'
              JOIN purchaseItems pi ON at.transactionId = pi.id
              JOIN purchases p     ON pi.purchaseId = p.id
              WHERE je.creditAccountId = ?
              GROUP BY p.supplierId
            ) created ON s.id = created.supplierId
            LEFT JOIN (
              SELECT pp.supplierId, SUM(je.amount) AS total
              FROM journalEntries je
              JOIN accountingTransaction at ON je.referenceId = at.id
                                           AND at.transactionType = 'PayablePayment'
              JOIN payablePayment pp ON at.transactionId = pp.id
              WHERE je.debitAccountId = ?
              GROUP BY pp.supplierId
            ) paid ON s.id = paid.supplierId
            WHERE COALESCE(created.total, 0) - COALESCE(paid.total, 0) > 0
            ORDER BY s.name
        """.trimIndent()
        val args = arrayOf(payablesAcct.toString(), payablesAcct.toString())
        return helper.fetchAll(rdb.rawQuery(sql, args)).toString()
    }

    fun getOpenPayables(supplierId: Int): String {
        val payablesAcct = helper.getAccountIdByCode("2000")
        val sql = """
            SELECT
              COALESCE(created.total, 0) - COALESCE(paid.total, 0) AS openBalance
            FROM (SELECT 1) dummy
            LEFT JOIN (
              SELECT SUM(je.amount) AS total
              FROM journalEntries je
              JOIN accountingTransaction at ON je.referenceId = at.id
                                           AND at.transactionType = 'Purchase'
              JOIN purchaseItems pi ON at.transactionId = pi.id
              JOIN purchases p     ON pi.purchaseId = p.id
              WHERE je.creditAccountId = ?
                AND p.supplierId = ?
            ) created ON 1=1
            LEFT JOIN (
              SELECT SUM(je.amount) AS total
              FROM journalEntries je
              JOIN accountingTransaction at ON je.referenceId = at.id
                                           AND at.transactionType = 'PayablePayment'
              JOIN payablePayment pp ON at.transactionId = pp.id
              WHERE je.debitAccountId = ?
                AND pp.supplierId = ?
            ) paid ON 1=1
        """.trimIndent()
        val args = arrayOf(
            payablesAcct.toString(), supplierId.toString(),
            payablesAcct.toString(), supplierId.toString()
        )
        rdb.rawQuery(sql, args).use { c ->
            val balance = if (c.moveToFirst()) c.getDouble(0) else 0.0
            return JSONObject().put("openBalance", balance).toString()
        }
    }

    fun savePayablePayment(json: String): String {
        return try {
            val obj             = JSONObject(json)
            val supplierId      = obj.getInt("supplierId")
            val amount          = obj.getDouble("amount")
            val paymentMethodId = obj.getInt("paymentMethodId")
            val notes           = obj.optString("notes", "")
            val date            = obj.optString("paymentDate", helper.nowISoDateOnly())

            // Resolve method name for accounting subType
            val methodName = rdb.rawQuery(
                "SELECT name FROM apPaymentMethods WHERE id = ?",
                arrayOf(paymentMethodId.toString())
            ).use { c -> if (c.moveToFirst()) c.getString(0) else throw IllegalArgumentException("Unknown paymentMethodId: $paymentMethodId") }

            val cv = ContentValues().apply {
                put("supplierId",      supplierId)
                put("paymentDate",     date)
                put("amount",          amount)
                put("paymentMethodId", paymentMethodId)
                put("notes",           notes)
                put("createDate",      helper.nowIso())
            }
            val paymentId = db.insert("payablePayment", null, cv).toInt()

            val input = AccountingTransactionInput(
                type      = "PayablePayment",
                subType   = methodName,
                table     = "payablePayment",
                refId     = paymentId,
                refId2    = 0,
                productId = 0,
                amount    = amount,
                date      = date,
                notes     = notes.ifBlank { null }
            )
            helper.insertAccountingTransactionAndPostJournal(input)

            JSONObject().put("id", paymentId).put("isGood", true).toString()
        } catch (e: Exception) {
            android.util.Log.e("ERP", "savePayablePayment failed", e)
            JSONObject().put("error", e.message ?: "Unknown").toString()
        }
    }

    fun getAveragePurchasedMilkCogs(productId: Int): String {
        val db = rdb
        val invAcct = helper.getAccountIdByCode("1001")

        val purchasedCost = db.rawQuery(
            """
      SELECT COALESCE(SUM(amount),0)
        FROM ${DatabaseHelper.T_JOURNAL}
       WHERE referenceType   = 'PurchaseAsset'
         AND debitAccountId  = ?
    """.trimIndent(), arrayOf(invAcct.toString())
        )
            .use { c -> if (c.moveToFirst()) c.getDouble(0) else 0.0 }

        val purchasedQty = db.rawQuery(
            """
      SELECT COALESCE(SUM(quantity),0)
        FROM ${DatabaseHelper.T_TRANSACTIONS}
       WHERE referenceType = 'PurchaseAsset'
         AND productId     = ?
    """.trimIndent(), arrayOf(productId.toString())
        )
            .use { c -> if (c.moveToFirst()) c.getDouble(0) else 0.0 }

        val avg = if (purchasedQty > 0) purchasedCost / purchasedQty else 0.0

        return JSONObject()
            .put("purchasedAvg", avg)
            .toString()
    }

    fun getPurchase(id: String): String =
        helper.fetchAll(
            rdb.rawQuery(
                """
            SELECT * FROM ${DatabaseHelper.T_PURCHASES} WHERE id = ?
        """, arrayOf(id)
            )
        ).toString()

    fun getPurchaseById(id: Int): PurchaseHeader? {
        val db = rdb
        val sql = """
      SELECT id, supplierId, purchaseDate, statusId, notes, createDate, updateDate
        FROM ${DatabaseHelper.T_PURCHASES}
       WHERE id = ?
    """.trimIndent()
        db.rawQuery(sql, arrayOf(id.toString())).use { c ->
            if (!c.moveToFirst()) return null
            return PurchaseHeader(
                id = c.getInt(0),
                supplierId = c.getInt(1),
                purchaseDate = c.getString(2),
                statusId = c.getInt(3),
                notes = c.getString(4) ?: "",
                createDate = c.getString(5),
                updateDate = c.getString(6)
            )
        }
    }

    fun getPurchaseItemsByPurchaseId(purchaseId: Int): List<PurchaseItem1> {
        val out = mutableListOf<PurchaseItem1>()
        val db = rdb
        val sql = """
      SELECT id, purchaseId, supplierItemId, quantity, uomId, pricePerUom
        FROM ${DatabaseHelper.T_PURCHASE_ITEMS}
       WHERE purchaseId = ?
    """.trimIndent()

        val selectionArgs = arrayOf(purchaseId.toString())

        db.rawQuery(sql, selectionArgs).use { c ->
            while (c.moveToNext()) {
                out.add(PurchaseItem1(
                    id = c.getInt(0),
                    purchaseId = c.getInt(1),
                    supplierItemId = c.getInt(2),
                    quantity = c.getDouble(3),
                    uomId = c.getInt(4),
                    pricePerUom = c.getDouble(5)
                ))
            }
        }
        return out
    }

    fun getPurchaseItems(purchaseId: String): String =
        helper.fetchAll(
            rdb.rawQuery(
                """
            SELECT * FROM ${DatabaseHelper.T_PURCHASE_ITEMS} WHERE purchaseId = ?
        """, arrayOf(purchaseId)
            )
        ).toString()

    fun getPurchaseStatus(): String =
        helper.fetchAll(rdb.rawQuery("SELECT * FROM ${DatabaseHelper.T_PURCHASE_STATUS}", null)).toString()

    fun getPurchases(): String =
        helper.fetchAll(rdb.rawQuery("SELECT * FROM ${DatabaseHelper.T_PURCHASES}", null)).toString()

    fun getSupplierPurchaseSummariesThisMonth(): String {
        val db = rdb
        val since = helper.firstOfMonthIso()
        val arr = JSONArray()

        val sql = """
            SELECT
                pr.supplierId,
                COUNT(DISTINCT pr.id) AS purchaseCount,
                SUM(pi.quantity * IFNULL(uc.conversionFactor, 1)) AS qtyPurchased,
                SUM(pi.price * pi.quantity) AS totalPrice
            FROM ${DatabaseHelper.T_PURCHASE_ITEMS} pi
            JOIN ${DatabaseHelper.T_PURCHASES} pr
                ON pi.purchaseId = pr.id
            JOIN ${DatabaseHelper.T_SUPPLIER_ITEMS} si
                ON pi.supplierItemId = si.id
            LEFT JOIN ${DatabaseHelper.T_PRODUCTS} p
                ON si.productId = p.id
            LEFT JOIN ${DatabaseHelper.T_UNIT_CONVERSIONS} uc
                ON uc.fromUomId = pi.uomId
               AND uc.toUomId   = p.baseUomId
            WHERE pr.purchaseDate >= ?
            GROUP BY pr.supplierId
            ORDER BY pr.supplierId
        """.trimIndent()

        db.rawQuery(sql, arrayOf(since)).use { c ->
            while (c.moveToNext()) {
                val obj = JSONObject().apply {
                    put("supplierId", c.getInt(0))
                    put("purchaseCount", c.getInt(1))
                    put("qtyPurchased", c.getDouble(2))
                    put("totalPrice", c.getDouble(3))
                }
                arr.put(obj)
            }
        }

        return arr.toString()
    }

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
            pur.purchaseDate AS purchaseDate
            , pro.name badgeName
            , (t.quantity * pi.price) badgeValues
        FROM transactions t
        JOIN products p          ON t.productId      = p.id
        JOIN uoms     u          ON t.uomId          = u.id
        LEFT JOIN categories c   ON p.categoryId     = c.id
        JOIN purchases pur       ON t.referenceId    = pur.id
                                AND t.referenceType  = 'Purchase'
                                AND t.transactionType = 'in'
        JOIN suppliers sup       ON pur.supplierId   = sup.id
        JOIN purchaseItems pi    ON pur.id           = pi.purchaseId
                                AND pi.id            = t.lineId
        JOIN products pro        ON pi.productId     = pro.id
        JOIN purchaseLineStatus pls ON pi.statusId   = pls.id
        WHERE date(pur.purchaseDate) BETWEEN ? AND ?
        ORDER BY pur.purchaseDate DESC, pur.id, pi.id
    """.trimIndent()

        val selectionArgs = arrayOf(start, end)
        return helper.fetchAll(rdb.rawQuery(sql, selectionArgs)).toString()
    }

    fun saveAssetPurchase(json: String): String {
        return try {
            val obj = JSONObject(json)
            val supplierId = obj.getInt("supplierId")
            val date = obj.getString("purchaseDate")
            val itemsArray = obj.getJSONArray("items")

            val cvHdr = ContentValues().apply {
                put("supplierId", supplierId)
                put("purchaseDate", date)
                put("statusId", helper.getStatusId("purchaseStatus", "Stocked"))
                put("createDate", helper.nowIso())
                put("updateDate", helper.nowIso())
            }
            val purchaseId = db.use { db ->
                db.insert(DatabaseHelper.T_PURCHASES, null, cvHdr).toInt()
            }

            for (i in 0 until itemsArray.length()) {
                val lineObj = itemsArray.getJSONObject(i)
                val productId = lineObj.getInt("productId")
                val uomId = lineObj.getInt("uomId")
                val quantity = lineObj.getDouble("quantity")
                val pricePerUom = lineObj.getDouble("pricePerUom")

                val cvLine = ContentValues().apply {
                    put("purchaseId", purchaseId)
                    put("productId", productId)
                    put("uomId", uomId)
                    put("statusId", helper.getStatusId("purchaseItems", "Stocked"))
                    put("price", pricePerUom)
                    put("quantity", quantity)
                }
                val lineId = db.insert(DatabaseHelper.T_PURCHASE_ITEMS, null, cvLine).toInt()

                val baseUomId = helper.getBaseUomId(productId)
                val factor = helper.getConversionFactor(uomId, baseUomId)
                val baseQty = quantity * factor

                helper.insertTransaction(
                    refType = "PurchaseAsset",
                    refId = purchaseId,
                    productId = productId,
                    lineId = lineId,
                    txnType = "in",
                    quantity = baseQty,
                    uomId = baseUomId,
                    txnDate = helper.nowIso(),
                    notes = null
                )

            }

            val totalAmt = (0 until itemsArray.length()).sumOf { idx ->
                val it = itemsArray.getJSONObject(idx)
                it.getDouble("quantity") * it.getDouble("pricePerUom")
            }

            val invAcct = helper.getAccountIdByCode("1001")
            val payAcct = helper.getAccountIdByCode("2000")
            helper.recordJournalEntry(
                refType = "PurchaseAsset",
                refId = purchaseId,
                debitId = invAcct,
                creditId = payAcct,
                amount = totalAmt,
                desc = "Purchased Milk Inventory #$purchaseId"
            )

            JSONObject().put("purchaseId", purchaseId).toString()
        } catch (ex: Exception) {
            android.util.Log.e("DB", "saveAssetPurchase failed", ex)
            JSONObject().put("error", ex.message ?: "Unknown error").toString()
        }
    }

    fun getPurchase(purchaseId: Int): String? {
        val db = rdb
        val cursor: Cursor = db.query(
            DatabaseHelper.T_PURCHASES,
            arrayOf("id", "supplierId", "purchaseDate", "statusId", "notes"),
            "id = ?",
            arrayOf(purchaseId.toString()),
            null, null, null
        )

        return cursor.use {
            if (!it.moveToFirst()) return null

            JSONObject().apply {
                put("id", it.getInt(it.getColumnIndexOrThrow("id")))
                put("supplierId", it.getInt(it.getColumnIndexOrThrow("supplierId")))
                put("purchaseDate", it.getString(it.getColumnIndexOrThrow("purchaseDate")))
                put("statusId", it.getInt(it.getColumnIndexOrThrow("statusId")))
                put("notes", it.getString(it.getColumnIndexOrThrow("notes")) ?: "")
            }.toString()
        }
    }

    fun getPurchaseItems(purchaseId: Int): String {
        val db = rdb
        val cursor: Cursor = db.query(
            DatabaseHelper.T_PURCHASE_ITEMS,
            arrayOf("id", "purchaseId", "supplierItemId", "statusId", "price", "quantity"),
            "purchaseId = ?",
            arrayOf(purchaseId.toString()),
            null, null, "id ASC"
        )

        val arr = JSONArray()
        cursor.use {
            while (it.moveToNext()) {
                val obj = JSONObject().apply {
                    put("id", it.getInt(it.getColumnIndexOrThrow("id")))
                    put("purchaseId", it.getInt(it.getColumnIndexOrThrow("purchaseId")))
                    put("supplierItemId", it.getInt(it.getColumnIndexOrThrow("supplierItemId")))
                    put("statusId", it.getInt(it.getColumnIndexOrThrow("statusId")))
                    put("pricePerUom", it.getDouble(it.getColumnIndexOrThrow("price")))
                    put("quantity", it.getDouble(it.getColumnIndexOrThrow("quantity")))
                }
                arr.put(obj)
            }
        }
        return arr.toString()
    }
}
