package com.example.dairypos.data.repository.reference

import android.database.Cursor
import android.util.Log
import com.example.dairypos.DatabaseHelper
import com.example.dairypos.model.InvoiceBalance
import com.example.dairypos.model.PaymentWithRemaining
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/** Invoice queries, payment tracking, and invoice generation. No accounting. */
class r5_InvoiceRepository(private val helper: DatabaseHelper) {
    private val db  get() = helper.writableDatabase
    private val rdb get() = helper.readableDatabase

    fun isInvoiceExists(custId: Int, monthId: Int): Boolean {
        val fmt = DateTimeFormatter.ISO_DATE
        val current = YearMonth.now()
        val target  = YearMonth.of(current.year, monthId)
        val startDate = target.atDay(1).format(fmt)
        val endDate   = (if (target == current) LocalDate.now() else target.atEndOfMonth()).format(fmt)
        return helper.fetchScalarInt(
            "SELECT COUNT(1) FROM sales s WHERE s.customerId = ? AND date(s.saleDate) BETWEEN ? AND ?",
            arrayOf(custId.toString(), startDate, endDate)
        ) > 0
    }

    fun getOpenInvoices(): String {
        return try {
            val sql = """
                SELECT I.id, I.customerId, C.name AS customerName, I.invoiceDate, I.total, I.status,
                       COALESCE(SUM(PA.appliedAmount), 0) AS totalPaid
                  FROM invoice I
                  LEFT JOIN customers C ON C.id = I.customerId
                  LEFT JOIN paymentApplied PA ON PA.appliedToInvoiceId = I.id
                 GROUP BY I.id
                HAVING (I.total - COALESCE(SUM(PA.appliedAmount), 0)) > 0.005
                 ORDER BY date(I.invoiceDate) ASC
            """.trimIndent()
            helper.fetchAll(rdb.rawQuery(sql, null)).toString()
        } catch (ex: Exception) {
            Log.e("r5Invoice", "getOpenInvoices error", ex)
            "[]"
        }
    }

    fun getPaidInvoices(): String {
        return try {
            val sql = """
                SELECT I.id, I.customerId, C.name AS customerName, I.invoiceDate, I.total, I.status,
                       COALESCE(SUM(PA.appliedAmount), 0) AS totalPaid
                  FROM invoice I
                  LEFT JOIN customers C ON C.id = I.customerId
                  LEFT JOIN paymentApplied PA ON PA.appliedToInvoiceId = I.id
                 GROUP BY I.id
                HAVING (I.total - COALESCE(SUM(PA.appliedAmount), 0)) <= 0.005
                 ORDER BY date(I.invoiceDate) DESC
            """.trimIndent()
            helper.fetchAll(rdb.rawQuery(sql, null)).toString()
        } catch (ex: Exception) {
            Log.e("r5Invoice", "getPaidInvoices error", ex)
            "[]"
        }
    }

    fun getInvoiceDetails(invoiceId: Int): String {
        try {
            val hdrSql = """
                SELECT I.id, I.customerId, I.invoiceDate, I.notes, I.total, I.status, C.name,
                       COALESCE(SUM(PA.appliedAmount), 0) AS paid,
                       (I.total - COALESCE(SUM(PA.appliedAmount), 0)) AS balance
                  FROM invoice I
                  LEFT JOIN customers C ON C.id = I.customerId
                  LEFT JOIN paymentApplied PA ON PA.appliedToInvoiceId = I.id
                 WHERE I.id = ?
                 GROUP BY I.id
            """.trimIndent()
            val headerJson = JSONObject()
            rdb.rawQuery(hdrSql, arrayOf(invoiceId.toString())).use { c ->
                if (!c.moveToFirst()) return JSONObject().put("error", "invoice not found").toString()
                headerJson.put("invoiceId",    c.getInt(0))
                headerJson.put("customerId",   c.getInt(1))
                headerJson.put("invoiceDate",  c.getString(2))
                headerJson.put("notes",        c.getString(3) ?: "")
                headerJson.put("total",        if (!c.isNull(4)) c.getDouble(4) else 0.0)
                headerJson.put("status",       c.getString(5) ?: "Draft")
                headerJson.put("customerName", c.getString(6) ?: "")
                headerJson.put("paid",         if (!c.isNull(7)) c.getDouble(7) else 0.0)
                headerJson.put("balance",      if (!c.isNull(8)) c.getDouble(8) else 0.0)
            }
            val linesSql = """
                WITH invoiceSales AS (
                  SELECT S.* FROM sales S JOIN invoiceSaleItems I ON S.id = I.saleId WHERE I.invoiceId = ?
                ),
                DailyAggregated AS (
                  SELECT S.customerId, date(S.saleDate) AS saleDay, SUM(S.quantity) AS quantity, S.rate
                    FROM invoiceSales S GROUP BY S.customerId, date(S.saleDate), S.rate
                ),
                RankedSales AS (
                  SELECT C.name AS customerName, D.saleDay, D.quantity, D.rate,
                         ROW_NUMBER() OVER (PARTITION BY D.quantity ORDER BY D.saleDay) AS rn,
                         julianday(D.saleDay) AS jd
                    FROM DailyAggregated D JOIN customers C ON C.id = D.customerId
                ),
                GroupsByDateRange AS (SELECT saleDay, quantity, rate, (jd - rn) AS groupKey FROM RankedSales),
                Aggregated AS (
                  SELECT MIN(saleDay) AS startDate, MAX(saleDay) AS endDate, quantity,
                         SUM(quantity) AS totalQty, SUM(quantity * rate) AS totalAmt
                    FROM GroupsByDateRange GROUP BY groupKey, quantity
                ),
                DateFormats AS (
                  SELECT A.startDate, A.endDate,
                         CAST(julianday(A.endDate) - julianday(A.startDate) + 1 AS TEXT) AS days,
                         strftime('%d', A.startDate) || '-' || CASE strftime('%m', A.startDate)
                           WHEN '01' THEN 'Jan' WHEN '02' THEN 'Feb' WHEN '03' THEN 'Mar'
                           WHEN '04' THEN 'Apr' WHEN '05' THEN 'May' WHEN '06' THEN 'Jun'
                           WHEN '07' THEN 'Jul' WHEN '08' THEN 'Aug' WHEN '09' THEN 'Sep'
                           WHEN '10' THEN 'Oct' WHEN '11' THEN 'Nov' WHEN '12' THEN 'Dec' END AS fromDay,
                         strftime('%d', A.endDate) || '-' || CASE strftime('%m', A.endDate)
                           WHEN '01' THEN 'Jan' WHEN '02' THEN 'Feb' WHEN '03' THEN 'Mar'
                           WHEN '04' THEN 'Apr' WHEN '05' THEN 'May' WHEN '06' THEN 'Jun'
                           WHEN '07' THEN 'Jul' WHEN '08' THEN 'Aug' WHEN '09' THEN 'Sep'
                           WHEN '10' THEN 'Oct' WHEN '11' THEN 'Nov' WHEN '12' THEN 'Dec' END AS toDay
                    FROM Aggregated A
                )
                SELECT DT.fromDay, DT.toDay, DT.days, A.quantity, A.totalQty, A.totalAmt
                  FROM Aggregated A
                  JOIN DateFormats DT ON A.startDate = DT.startDate AND A.endDate = DT.endDate
                 ORDER BY A.startDate
            """.trimIndent()
            val lines = JSONArray()
            rdb.rawQuery(linesSql, arrayOf(invoiceId.toString())).use { c ->
                while (c.moveToNext()) {
                    lines.put(JSONObject().apply {
                        put("fromDay",  c.getString(0))
                        put("toDay",    c.getString(1))
                        put("days",     c.getString(2))
                        put("quantity", if (!c.isNull(3)) c.getDouble(3) else 0.0)
                        put("totalQty", if (!c.isNull(4)) c.getDouble(4) else 0.0)
                        put("totalAmt", if (!c.isNull(5)) c.getDouble(5) else 0.0)
                    })
                }
            }
            return JSONObject().put("header", headerJson).put("lines", lines).toString()
        } catch (ex: Exception) {
            Log.e("r5Invoice", "getInvoiceDetails error", ex)
            return JSONObject().put("error", ex.message ?: "Unknown error").toString()
        }
    }

    fun getCustomerOpenInvoices(customerId: Int): List<Map<String, Any>> {
        val list = mutableListOf<Map<String, Any>>()
        val sql = """
            SELECT i.id, i.invoiceDate, i.total,
                   COALESCE(SUM(pa.appliedAmount), 0) AS paid,
                   (i.total - COALESCE(SUM(pa.appliedAmount), 0)) AS balance
              FROM invoice i
              LEFT JOIN paymentApplied pa ON pa.appliedToInvoiceId = i.id
             WHERE i.customerId = ?
             GROUP BY i.id
            HAVING balance > 0.005
             ORDER BY i.invoiceDate ASC, i.id ASC
        """.trimIndent()
        rdb.rawQuery(sql, arrayOf(customerId.toString())).use { c ->
            while (c.moveToNext()) {
                list.add(mapOf(
                    "id"          to c.getLong(0),
                    "invoiceDate" to c.getString(1),
                    "total"       to c.getDouble(2),
                    "paid"        to c.getDouble(3),
                    "balance"     to c.getDouble(4)
                ))
            }
        }
        return list
    }

    fun getCustomerOpenPayments(customerId: String): String {
        return try {
            val sql = """
                SELECT p.id, p.receivedAmount,
                       COALESCE(SUM(pa.appliedAmount), 0) AS applied
                  FROM paymentReceived p
                  LEFT JOIN paymentApplied pa ON pa.paymentReceivedId = p.id
                 WHERE p.customerId = ?
                 GROUP BY p.id
                HAVING (p.receivedAmount - COALESCE(SUM(pa.appliedAmount), 0)) > 0.005
                 ORDER BY p.receivedDate ASC
            """.trimIndent()
            val arr = JSONArray()
            rdb.rawQuery(sql, arrayOf(customerId)).use { c ->
                while (c.moveToNext()) {
                    arr.put(JSONObject().apply {
                        put("id",             c.getLong(0))
                        put("receivedAmount", c.getDouble(1))
                        put("applied",        c.getDouble(2))
                        put("remaining",      c.getDouble(1) - c.getDouble(2))
                    })
                }
            }
            arr.toString()
        } catch (ex: Exception) {
            Log.e("r5Invoice", "getCustomerOpenPayments error", ex)
            "[]"
        }
    }

    fun getOpenInvoicesWithBalance(customerId: Int): List<InvoiceBalance> {
        val list = mutableListOf<InvoiceBalance>()
        val sql = """
            SELECT i.id, i.total, COALESCE(SUM(pa.appliedAmount), 0) AS paid
              FROM invoice i
              LEFT JOIN paymentApplied pa ON pa.appliedToInvoiceId = i.id
             WHERE i.customerId = ?
             GROUP BY i.id
            HAVING (i.total - COALESCE(SUM(pa.appliedAmount), 0)) > 0.005
             ORDER BY i.invoiceDate ASC
        """.trimIndent()
        rdb.rawQuery(sql, arrayOf(customerId.toString())).use { c ->
            while (c.moveToNext()) list.add(InvoiceBalance(c.getLong(0), c.getDouble(1), c.getDouble(2)))
        }
        return list
    }

    fun getOpenPaymentsWithRemaining(customerId: Int): List<PaymentWithRemaining> {
        val list = mutableListOf<PaymentWithRemaining>()
        val sql = """
            SELECT p.id, p.receivedAmount, COALESCE(SUM(pa.appliedAmount), 0) AS applied
              FROM paymentReceived p
              LEFT JOIN paymentApplied pa ON pa.paymentReceivedId = p.id
             WHERE p.customerId = ?
             GROUP BY p.id
            HAVING (p.receivedAmount - COALESCE(SUM(pa.appliedAmount), 0)) > 0.005
             ORDER BY p.receivedDate ASC
        """.trimIndent()
        rdb.rawQuery(sql, arrayOf(customerId.toString())).use { c ->
            while (c.moveToNext()) list.add(PaymentWithRemaining(c.getLong(0), c.getDouble(1), c.getDouble(2)))
        }
        return list
    }

    fun generateCustomerSalesInvoice(customerId: Int, monthId: Int): List<List<String?>> {
        val result = mutableListOf<List<String?>>()
        try {
            val fmt = DateTimeFormatter.ISO_DATE
            val current = YearMonth.now()
            val target  = YearMonth.of(current.year, monthId)
            val startDate = target.atDay(1).format(fmt)
            val endDate   = (if (target == current) LocalDate.now() else target.atEndOfMonth()).format(fmt)
            val sql = buildInvoiceReportSql()
            var cursor: Cursor? = null
            try {
                cursor = rdb.rawQuery(sql, arrayOf(customerId.toString(), startDate, endDate))
                val colCount = cursor.columnCount
                while (cursor.moveToNext()) {
                    val row = mutableListOf<String?>()
                    for (i in 0 until colCount) row.add(cursor.getString(i))
                    result.add(row)
                }
            } finally { cursor?.close() }
        } catch (ex: Exception) {
            Log.e("r5Invoice", "generateCustomerSalesInvoice failed", ex)
        }
        return result
    }

    fun generateCustomerSalesInvoiceString(customerId: Int, monthId: Int): String {
        return try {
            val fmt = DateTimeFormatter.ISO_DATE
            val current = YearMonth.now()
            val target  = YearMonth.of(current.year, monthId)
            val startDate = target.atDay(1).format(fmt)
            val endDate   = (if (target == current) LocalDate.now() else target.atEndOfMonth()).format(fmt)
            val sql = buildInvoiceReportSql()
            val reportArr = JSONArray()
            var cursor: Cursor? = null
            try {
                cursor = rdb.rawQuery(sql, arrayOf(customerId.toString(), startDate, endDate))
                val colCount = cursor.columnCount
                while (cursor.moveToNext()) {
                    val rowArr = JSONArray()
                    for (i in 0 until colCount) rowArr.put(cursor.getString(i) ?: JSONObject.NULL)
                    reportArr.put(rowArr)
                }
            } finally { cursor?.close() }
            JSONObject().put("status", "success").put("data", reportArr).toString()
        } catch (ex: Exception) {
            Log.e("r5Invoice", "generateCustomerSalesInvoiceString failed", ex)
            JSONObject().put("status", "error").put("message", ex.message ?: "Unknown").toString()
        }
    }

    private fun buildInvoiceReportSql() = """
        WITH DailyAggregated AS (
          SELECT S.customerId, date(S.saleDate) AS saleDay, SUM(S.quantity) AS quantity, S.rate
            FROM sales S WHERE S.customerId = ? AND date(S.saleDate) BETWEEN ? AND ?
           GROUP BY S.customerId, date(S.saleDate), S.rate
        ), RankedSales AS (
          SELECT C.name AS customerName, D.saleDay, D.quantity, D.rate,
                 ROW_NUMBER() OVER (PARTITION BY D.quantity ORDER BY D.saleDay) AS rn,
                 julianday(D.saleDay) AS jd
            FROM DailyAggregated D JOIN customers C ON C.id = D.customerId
        ), GroupsbyDateRange AS (
          SELECT saleDay, quantity, rate, (jd - rn) AS groupKey FROM RankedSales
        ), Aggregated AS (
          SELECT MIN(saleDay) AS startDate, MAX(saleDay) AS endDate, quantity,
                 SUM(quantity) AS totalQty, SUM(quantity * rate) AS totalAmt
            FROM GroupsbyDateRange GROUP BY groupKey, quantity
        ), ReportHeader AS (
          SELECT 'Milk invoice for ' || CASE strftime('%m', MIN(startDate))
            WHEN '01' THEN 'January' WHEN '02' THEN 'February' WHEN '03' THEN 'March'
            WHEN '04' THEN 'April'   WHEN '05' THEN 'May'      WHEN '06' THEN 'June'
            WHEN '07' THEN 'July'    WHEN '08' THEN 'August'   WHEN '09' THEN 'September'
            WHEN '10' THEN 'October' WHEN '11' THEN 'November' WHEN '12' THEN 'December'
          END || ' ' || strftime('%Y', MIN(startDate)) AS monthYear FROM Aggregated
        ), DateFormats AS (
          SELECT A.startDate, A.endDate,
                 CAST(julianday(A.endDate) - julianday(A.startDate) + 1 AS TEXT) AS days,
                 strftime('%d', A.startDate) || '-' || CASE strftime('%m', A.startDate)
                   WHEN '01' THEN 'Jan' WHEN '02' THEN 'Feb' WHEN '03' THEN 'Mar'
                   WHEN '04' THEN 'Apr' WHEN '05' THEN 'May' WHEN '06' THEN 'Jun'
                   WHEN '07' THEN 'Jul' WHEN '08' THEN 'Aug' WHEN '09' THEN 'Sep'
                   WHEN '10' THEN 'Oct' WHEN '11' THEN 'Nov' WHEN '12' THEN 'Dec' END AS fromDay,
                 strftime('%d', A.endDate) || '-' || CASE strftime('%m', A.endDate)
                   WHEN '01' THEN 'Jan' WHEN '02' THEN 'Feb' WHEN '03' THEN 'Mar'
                   WHEN '04' THEN 'Apr' WHEN '05' THEN 'May' WHEN '06' THEN 'Jun'
                   WHEN '07' THEN 'Jul' WHEN '08' THEN 'Aug' WHEN '09' THEN 'Sep'
                   WHEN '10' THEN 'Oct' WHEN '11' THEN 'Nov' WHEN '12' THEN 'Dec' END AS toDay
            FROM Aggregated A
        )
        SELECT (SELECT monthYear FROM ReportHeader) AS A, NULL AS B, NULL AS C, NULL AS D, NULL AS E, NULL AS F
        UNION ALL SELECT 'From Atiq Ur Rehman Contact # 0000 0606700', NULL, NULL, NULL, NULL, NULL
        UNION ALL SELECT 'Bill To:', (SELECT customerName FROM RankedSales GROUP BY customerName), NULL, NULL, NULL, NULL
        UNION ALL SELECT NULL, NULL, NULL, NULL, NULL, NULL
        UNION ALL SELECT 'From','To','Days','Qty','Total Qty','amount'
        UNION ALL
        SELECT DT.fromDay, DT.toDay, DT.days, printf('%.2f', A.quantity),
               printf('%.2f', A.totalQty), 'Rs ' || printf('%,.2f', A.totalAmt)
          FROM Aggregated A JOIN DateFormats DT ON A.startDate = DT.startDate AND A.endDate = DT.endDate
        UNION ALL SELECT NULL, NULL, NULL, NULL, NULL, NULL
        UNION ALL SELECT NULL, NULL, 'Payment Method', NULL, NULL, NULL
        UNION ALL SELECT NULL, NULL, 'Bank name: Standard Chartered', NULL, NULL, NULL
        UNION ALL SELECT NULL, NULL, 'Account name: ATIQ UR REHMAN', NULL, NULL, NULL
        UNION ALL SELECT NULL, NULL, 'IBAN: PK95SCBL0000001502678401', NULL, NULL, NULL
    """.trimIndent()
}
