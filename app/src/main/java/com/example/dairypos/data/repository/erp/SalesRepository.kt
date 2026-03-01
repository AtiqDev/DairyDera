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

class SalesRepository(private val helper: DatabaseHelper) {
    private val db get() = helper.writableDatabase
    private val rdb get() = helper.readableDatabase

    fun getAllSales(): String =
        helper.fetchAll(rdb.rawQuery("SELECT * FROM ${DatabaseHelper.T_SALES}", null)).toString()

    fun getSaleReport(fromDate: String, toDate: String): String {
        val fmt   = DateTimeFormatter.ISO_LOCAL_DATE
        val start = LocalDate.parse(fromDate, fmt).format(fmt)
        val end   = LocalDate.parse(toDate,   fmt).format(fmt)

        val sql = """
        SELECT
            c.name                                              AS customer,
            strftime('%m/%d/%Y %H:%M', s.saleDate)             AS saleDate,
            s.quantity                                          AS quantity,
            (s.rate * s.quantity)                              AS saleAmount,
            strftime('%m/%d/%Y', date(s.saleDate))             AS badgeName,
            (s.rate * s.quantity)                              AS badgeValues
        FROM sales s
        JOIN customers c ON s.customerId = c.id
        WHERE date(s.saleDate) BETWEEN date('$start') AND date('$end')
        ORDER BY s.saleDate DESC
    """.trimIndent()

        return helper.fetchAll(rdb.rawQuery(sql, null)).toString()
    }

    fun getSalesPerMonthToDate(): String {
        val db = rdb
        val now = helper.nowIso()
        val defaultJson = "[]"

        return try {
            db.rawQuery(
                """
            SELECT
              strftime('%Y-%m', saleDate) AS month,
              COUNT(*) AS saleCount,
              SUM(quantity) AS totalquantity,
              SUM(quantity * rate) AS totalAmount
            FROM Sales
            WHERE saleDate <= ?
            GROUP BY month
            ORDER BY month DESC
            """.trimIndent(),
                arrayOf(now)
            ).use { cursor ->
                val result = JSONArray()
                while (cursor.moveToNext()) {
                    val obj = JSONObject().apply {
                        put("month", cursor.getString(0))
                        put("saleCount", cursor.getInt(1))
                        put("totalQuantity", cursor.getDouble(2))
                        put("totalAmount", cursor.getDouble(3))
                    }
                    result.put(obj)
                }
                result.toString()
            }
        } catch (e: Exception) {
            Log.e("DatabaseHelper", "getSalesPerMonthToDate failed", e)
            defaultJson
        }
    }

    fun getCustomerSalesSummariesThisMonth(): String {
        val db = rdb
        val since = helper.firstOfMonthIso()
        val sql = """
            SELECT customerId,
                 COUNT(*)               AS salesCount,
                 SUM(quantity)          AS qtySold,
                 SUM(quantity * rate)   AS amountTotal
            FROM Sales
           WHERE saleDate >= ?
           GROUP BY customerId
        """.trimIndent()

        val cursor = db.rawQuery(sql, arrayOf(since))
        val arr = JSONArray()
        cursor.use {
            while (it.moveToNext()) {
                val obj = JSONObject().apply {
                    put("customerId", it.getInt(0))
                    put("salesCount", it.getInt(1))
                    put("qtySold", it.getDouble(2))
                    put("amountTotal", it.getDouble(3))
                }
                arr.put(obj)
            }
        }
        return arr.toString()
    }

    fun saveSale(json: String): String {
        try {
            val obj = JSONObject(json)
            val saleDate = obj.getString("saleDate")
            val custId = obj.getInt("customerId")
            val totalSaleQty = obj.getDouble("quantity")
            val feedbackNotes = obj.optString("feedbackNotes", "")
            val createDate = obj.optString("createDate", helper.nowIso())
            val updateDate = obj.optString("updateDate", helper.nowIso())

            if (totalSaleQty <= 0) {
                throw Exception("Sale quantity must be greater than zero.")
            }

            val allBatches = helper.getAvailableDailyFinancialSummaries()
            if (allBatches.isEmpty()) {
                throw Exception("No available batches to sell from.")
            }
            val totalAvailableQty = allBatches.sumOf { it.qtyAvailable }

            if (totalSaleQty > totalAvailableQty) {
                throw Exception("Insufficient stock. Required: $totalSaleQty, Available: $totalAvailableQty")
            }

            var remainingQtyToSell = totalSaleQty
            val saleResultIds = mutableListOf<Int>()

            for (batch in allBatches) {
                if (remainingQtyToSell <= 0) {
                    break
                }

                val qtyToSellFromThisBatch = minOf(remainingQtyToSell, batch.qtyAvailable)

                val saleInput = SaleInput(
                    id = 0,
                    customerId = custId,
                    saleDate = saleDate,
                    quantity = qtyToSellFromThisBatch,
                    feedbackNotes = feedbackNotes,
                    createDate = createDate,
                    updateDate = updateDate
                )

                val resultJsonString = recordSale(batch.batchId, saleInput)
                val resultObj = JSONObject(resultJsonString)

                if (resultObj.has("error")) {
                    throw Exception("Failed to record sale for batch ${batch.productionDate}: ${resultObj.getString("error")}")
                }

                saleResultIds.add(resultObj.optInt("id", -1))
                remainingQtyToSell -= qtyToSellFromThisBatch
            }

            if (remainingQtyToSell > 0.001) {
                throw Exception("Failed to distribute sale completely. Remainder: $remainingQtyToSell")
            }

            return JSONObject().put("saleIds", JSONArray(saleResultIds)).toString()

        } catch (ex: Exception) {
            Log.e("DatabaseHelper", "Error in saveSale(): ${ex.message}", ex)
            return JSONObject()
                .put("error", ex.message ?: "Unknown error")
                .put("stack", ex.stackTraceToString())
                .toString()
        }
    }

    fun recordSale(batchId: Int, input: SaleInput): String {
        return try {
            val custId = input.customerId
            val saleDate = input.saleDate
            val qty = input.quantity

            val queryCustomerRate = """
            SELECT COALESCE(
                NULLIF(c.rate, 0),
                (SELECT spr.rate FROM SellableProductRates spr
                 JOIN products p ON spr.productId = p.id
                 WHERE p.name = 'Milk'
                 LIMIT 1)
            ) AS rate
            FROM customers c
            WHERE c.id = ?
        """.trimIndent()

            val productId = helper.getProductIdByCategory("Product")
            val rate = helper.fetchScalarDouble(queryCustomerRate, arrayOf(custId.toString()))
            val newSaleId: Int

            val cv = ContentValues().apply {
                put("customerId", custId)
                put("saleDate", saleDate)
                put("quantity", qty)
                put("rate", rate)
                put("statusId", helper.getStatusId("sales", "Complete"))
                put("feedbackNotes", input.feedbackNotes)
                put("updateDate", input.updateDate)
            }

            newSaleId = if (input.id > 0) {
                db.update(DatabaseHelper.T_SALES, cv, "id=?", arrayOf(input.id.toString()))
            } else {
                cv.put("createDate", input.createDate)
                db.insert(DatabaseHelper.T_SALES, null, cv).toInt()
            }

            val baseUomId = helper.getBaseUomId(productId)
            helper.insertTransaction(
                refType = "productionBatches",
                refId = batchId,
                productId = productId,
                lineId = 0,
                txnType = "out",
                quantity = qty,
                uomId = baseUomId,
                txnDate = saleDate,
                notes = "Sold $qty units"
            )
            helper.recalibrateStock(productId)

            val saleAmount = qty * rate
            val saleTxn = AccountingTransactionInput(
                type = "Sale",
                subType = "Product",
                table = "sales",
                refId = newSaleId,
                refId2 = batchId,
                productId = productId,
                amount = saleAmount,
                unitPrice = rate,
                date = null,
                notes = null
            )
            helper.insertAccountingTransactionAndPostJournal(saleTxn)

            val monthQuery = """
            SELECT id FROM invoice
            WHERE customerId = ?
              AND strftime('%Y-%m', invoiceDate) = strftime('%Y-%m', ?)
        """.trimIndent()
            val existingInvoiceId = helper.fetchScalarInt(monthQuery, arrayOf(custId.toString(), saleDate))

            val invoiceId = if (existingInvoiceId != 0) {
                existingInvoiceId
            } else {
                val cvInv = ContentValues().apply {
                    put("customerId", custId)
                    put("invoiceDate", saleDate)
                    put("notes", "Auto-created monthly invoice")
                    put("createDate", helper.nowIso())
                    put("status", "Draft")
                    put("total", 0)
                }
                db.insert("invoice", null, cvInv).toInt()
            }

            val selSalesQuery = """
            SELECT S.id
            FROM sales S
            LEFT JOIN invoiceSaleItems I ON S.id = I.saleId
            WHERE S.customerId = ?
              AND strftime('%Y-%m', S.saleDate) = strftime('%Y-%m', ?)
              AND I.saleId IS NULL
        """.trimIndent()

            val saleIds = mutableListOf<Int>()
            db.rawQuery(selSalesQuery, arrayOf(custId.toString(), saleDate)).use {
                while (it.moveToNext()) {
                    saleIds.add(it.getInt(0))
                }
            }

            saleIds.forEach { sid ->
                val cvLink = ContentValues().apply {
                    put("invoiceId", invoiceId)
                    put("saleId", sid)
                }
                db.insertWithOnConflict(
                    "invoiceSaleItems",
                    null,
                    cvLink,
                    SQLiteDatabase.CONFLICT_IGNORE
                )
            }

            val totalQuery = """
            SELECT IFNULL(SUM(S.quantity * S.rate), 0)
            FROM Sales S
            JOIN invoiceSaleItems I ON S.id = I.saleId
            WHERE I.invoiceId = ?
        """.trimIndent()
            val total = helper.fetchScalarDouble(totalQuery, arrayOf(invoiceId.toString())) ?: 0.0

            val cvUpd = ContentValues().apply { put("total", total) }
            db.update("invoice", cvUpd, "id=?", arrayOf(invoiceId.toString()))

            JSONObject().put("id", newSaleId).toString()

        } catch (ex: Exception) {
            Log.e("DatabaseHelper", "Error in recordSale(): ${ex.message}", ex)
            JSONObject()
                .put("error", ex.message ?: "Unknown error")
                .put("stack", ex.stackTraceToString())
                .toString()
        }
    }
}
