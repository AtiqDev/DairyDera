package com.example.dairypos.data.repository.operational

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.example.dairypos.DatabaseHelper
import com.example.dairypos.data.repository.contracts.IAccountingEngine
import com.example.dairypos.data.repository.contracts.OperationalEvent
import com.example.dairypos.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Operational — milk sales recording. Triggers Sale and BatchSoldCogs journal entries. */
class r3_SalesRepository(
    private val helper: DatabaseHelper,
    private val engine: IAccountingEngine
) {
    private val db  get() = helper.writableDatabase
    private val rdb get() = helper.readableDatabase

    fun getAllSales(): String =
        helper.fetchAll(rdb.rawQuery("SELECT * FROM ${DatabaseHelper.T_SALES}", null)).toString()

    fun getSaleReport(fromDate: String, toDate: String): String {
        val fmt   = DateTimeFormatter.ISO_LOCAL_DATE
        val start = LocalDate.parse(fromDate, fmt).format(fmt)
        val end   = LocalDate.parse(toDate,   fmt).format(fmt)
        val sql = """
            SELECT
                c.name                                          AS customer,
                strftime('%m/%d/%Y %H:%M', s.saleDate)         AS saleDate,
                s.quantity                                      AS quantity,
                (s.rate * s.quantity)                          AS saleAmount,
                strftime('%m/%d/%Y', date(s.saleDate))         AS badgeName,
                (s.rate * s.quantity)                          AS badgeValues
              FROM sales s
              JOIN customers c ON s.customerId = c.id
             WHERE date(s.saleDate) BETWEEN date('$start') AND date('$end')
             ORDER BY s.saleDate DESC
        """.trimIndent()
        return helper.fetchAll(rdb.rawQuery(sql, null)).toString()
    }

    fun getSalesPerMonthToDate(): String {
        val now = helper.nowIso()
        return try {
            rdb.rawQuery(
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
                    result.put(JSONObject().apply {
                        put("month",         cursor.getString(0))
                        put("saleCount",     cursor.getInt(1))
                        put("totalQuantity", cursor.getDouble(2))
                        put("totalAmount",   cursor.getDouble(3))
                    })
                }
                result.toString()
            }
        } catch (e: Exception) {
            Log.e("r3Sales", "getSalesPerMonthToDate failed", e)
            "[]"
        }
    }

    fun getCustomerSalesSummariesThisMonth(): String {
        val since = helper.firstOfMonthIso()
        val sql = """
            SELECT customerId,
                   COUNT(*)             AS salesCount,
                   SUM(quantity)        AS qtySold,
                   SUM(quantity * rate) AS amountTotal
              FROM Sales
             WHERE saleDate >= ?
             GROUP BY customerId
        """.trimIndent()
        val cursor = rdb.rawQuery(sql, arrayOf(since))
        val arr = JSONArray()
        cursor.use {
            while (it.moveToNext()) {
                arr.put(JSONObject().apply {
                    put("customerId",  it.getInt(0))
                    put("salesCount",  it.getInt(1))
                    put("qtySold",     it.getDouble(2))
                    put("amountTotal", it.getDouble(3))
                })
            }
        }
        return arr.toString()
    }

    fun saveSale(json: String): String {
        try {
            val obj              = JSONObject(json)
            val saleDate         = obj.getString("saleDate")
            val custId           = obj.getInt("customerId")
            val totalSaleQty     = obj.getDouble("quantity")
            val feedbackNotes    = obj.optString("feedbackNotes", "")
            val createDate       = obj.optString("createDate", helper.nowIso())
            val updateDate       = obj.optString("updateDate", helper.nowIso())

            if (totalSaleQty <= 0) throw Exception("Sale quantity must be greater than zero.")

            val allBatches = getAvailableDailyFinancialSummaries()
            if (allBatches.isEmpty()) throw Exception("No available batches to sell from.")
            val totalAvailableQty = allBatches.sumOf { it.qtyAvailable }
            if (totalSaleQty > totalAvailableQty)
                throw Exception("Insufficient stock. Required: $totalSaleQty, Available: $totalAvailableQty")

            var remainingQtyToSell = totalSaleQty
            val saleResultIds = mutableListOf<Int>()

            for (batch in allBatches) {
                if (remainingQtyToSell <= 0) break
                val qtyToSellFromThisBatch = minOf(remainingQtyToSell, batch.qtyAvailable)
                val saleInput = SaleInput(
                    id = 0, customerId = custId, saleDate = saleDate,
                    quantity = qtyToSellFromThisBatch, feedbackNotes = feedbackNotes,
                    createDate = createDate, updateDate = updateDate
                )
                val resultJsonString = recordSale(batch.batchId, saleInput)
                val resultObj = JSONObject(resultJsonString)
                if (resultObj.has("error"))
                    throw Exception("Failed to record sale for batch ${batch.productionDate}: ${resultObj.getString("error")}")
                saleResultIds.add(resultObj.optInt("id", -1))
                remainingQtyToSell -= qtyToSellFromThisBatch
            }

            if (remainingQtyToSell > 0.001)
                throw Exception("Failed to distribute sale completely. Remainder: $remainingQtyToSell")

            return JSONObject().put("saleIds", JSONArray(saleResultIds)).toString()
        } catch (ex: Exception) {
            Log.e("r3Sales", "saveSale failed: ${ex.message}", ex)
            return JSONObject().put("error", ex.message ?: "Unknown error").put("stack", ex.stackTraceToString()).toString()
        }
    }

    fun recordSale(batchId: Int, input: SaleInput): String {
        return try {
            val custId   = input.customerId
            val saleDate = input.saleDate
            val qty      = input.quantity

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
            val rate      = helper.fetchScalarDouble(queryCustomerRate, arrayOf(custId.toString()))
            val newSaleId: Int

            val cv = ContentValues().apply {
                put("customerId",    custId)
                put("saleDate",      saleDate)
                put("quantity",      qty)
                put("rate",          rate)
                put("statusId",      helper.getStatusId("sales", "Complete"))
                put("feedbackNotes", input.feedbackNotes)
                put("updateDate",    input.updateDate)
                put("updatedOn",     System.currentTimeMillis())
            }
            newSaleId = if (input.id > 0) {
                db.update(DatabaseHelper.T_SALES, cv, "id=?", arrayOf(input.id.toString()))
            } else {
                cv.put("createDate", input.createDate)
                db.insert(DatabaseHelper.T_SALES, null, cv).toInt()
            }

            val baseUomId = helper.getBaseUomId(productId)
            helper.insertTransaction(
                refType = "productionBatches", refId = batchId, productId = productId,
                lineId = 0, txnType = "out", quantity = qty, uomId = baseUomId,
                txnDate = saleDate, notes = "Sold $qty units"
            )
            helper.recalibrateStock(productId)

            val saleAmount = qty * rate
            engine.processEvent(OperationalEvent(
                transactionTypeName = "Sale",
                subType             = "Product",
                tableName           = "sales",
                entityId            = newSaleId,
                amount              = saleAmount,
                refId2              = batchId,
                productId           = productId,
                unitPrice           = rate
            ))

            val unitCost = helper.fetchScalarDouble(
                "SELECT unitCostPerLiter FROM v_DailyFinancialsSummary WHERE batchId = ?",
                arrayOf(batchId.toString())
            ) ?: 0.0
            if (unitCost > 0) {
                engine.processEvent(OperationalEvent(
                    transactionTypeName = "BatchSoldCogs",
                    subType             = null,
                    tableName           = "sales",
                    entityId            = newSaleId,
                    amount              = qty * unitCost,
                    refId2              = batchId,
                    productId           = productId,
                    unitPrice           = unitCost
                ))
            }

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
                    put("customerId",  custId)
                    put("invoiceDate", saleDate)
                    put("notes",       "Auto-created monthly invoice")
                    put("createDate",  helper.nowIso())
                    put("status",      "Draft")
                    put("total",       0)
                    put("updatedOn",   System.currentTimeMillis())
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
                while (it.moveToNext()) saleIds.add(it.getInt(0))
            }
            saleIds.forEach { sid ->
                db.insertWithOnConflict("invoiceSaleItems", null,
                    ContentValues().apply { put("invoiceId", invoiceId); put("saleId", sid) },
                    SQLiteDatabase.CONFLICT_IGNORE)
            }

            val total = helper.fetchScalarDouble(
                "SELECT IFNULL(SUM(S.quantity * S.rate), 0) FROM Sales S JOIN invoiceSaleItems I ON S.id = I.saleId WHERE I.invoiceId = ?",
                arrayOf(invoiceId.toString())
            ) ?: 0.0
            db.update("invoice", ContentValues().apply { put("total", total); put("updatedOn", System.currentTimeMillis()) }, "id=?", arrayOf(invoiceId.toString()))

            JSONObject().put("id", newSaleId).toString()
        } catch (ex: Exception) {
            Log.e("r3Sales", "recordSale failed: ${ex.message}", ex)
            JSONObject().put("error", ex.message ?: "Unknown error").put("stack", ex.stackTraceToString()).toString()
        }
    }

    private fun getAvailableDailyFinancialSummaries(): List<DailyFinancialsSummary> {
        val sql = """
            SELECT batchId, productionDate, totalQtyProduced, totalQtySold, qtyAvailable,
                   totalDailyInputCostAmt, totalDailySalesAmt, unitCostPerLiter, totalCogs, netProfitOrLoss
              FROM v_DailyFinancialsSummary
             WHERE qtyAvailable > 0
             ORDER BY productionDate ASC
        """.trimIndent()
        val out = mutableListOf<DailyFinancialsSummary>()
        rdb.rawQuery(sql, null).use {
            while (it.moveToNext()) {
                out += DailyFinancialsSummary(
                    batchId = it.getInt(0), productionDate = it.getString(1),
                    totalQtyProduced = it.getDouble(2), totalQtySold = it.getDouble(3),
                    qtyAvailable = it.getDouble(4), totalDailyInputCostAmt = it.getDouble(5),
                    totalDailySalesAmt = it.getDouble(6), unitCostPerLiter = it.getDouble(7),
                    totalCogs = it.getDouble(8), netProfitOrLoss = it.getDouble(9)
                )
            }
        }
        return out
    }
}
