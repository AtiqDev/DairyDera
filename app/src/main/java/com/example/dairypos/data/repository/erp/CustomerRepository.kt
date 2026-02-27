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
import java.text.SimpleDateFormat
import java.util.*

class CustomerRepository(private val helper: DatabaseHelper) {
    private val db get() = helper.writableDatabase
    private val rdb get() = helper.readableDatabase

    fun saveCustomer(json: String): String {
        val obj = JSONObject(json)
        val id = obj.optInt("id", 0)
        val cv = ContentValues().apply {
            put("name", obj.getString("name"))
            put("address", obj.optString("address", ""))
            put("phone", obj.optString("phone", ""))
            put("rate", obj.getInt("rate"))
            put("quantity", obj.getDouble("quantity"))
            put("classId", obj.getInt("classId"))
            put("updateDate", obj.optString("updateDate", obj.optString("createDate", "")))
        }
        val newId = db.use { db ->
            if (id > 0) {
                db.update(DatabaseHelper.T_CUST, cv, "id=?", arrayOf(id.toString()))
                id
            } else {
                cv.put("createDate", obj.optString("createDate", helper.nowIso()))
                db.insert(DatabaseHelper.T_CUST, null, cv).toInt()
            }
        }
        return JSONObject().put("id", newId).toString()
    }

    fun getAllCustomers(): String =
        helper.fetchAll(
            rdb.rawQuery(
                "SELECT CU.id, CU.name, CU.phone, CU.quantity, CU.rate, CU.classId, CU.createDate, CU.updateDate, CL.name as className\n" +
                        "FROM Customers CU LEFT JOIN Classes CL ON CU.classId = CL.id", null
            )
        ).toString()

    fun getAllCustomersList(): List<CustomerDto> {
        val result = mutableListOf<CustomerDto>()
        val sql = """
        SELECT
            CU.id, CU.name, CU.phone, CU.quantity, CU.rate,
            CU.classId, CU.createDate, CU.updateDate,
            CL.name AS className
        FROM Customers CU
        LEFT JOIN Classes CL ON CU.classId = CL.id
    """.trimIndent()

        try {
            val cursor = rdb.rawQuery(sql, null)
            cursor.use {
                val idCol = cursor.getColumnIndexOrThrow("id")
                val nameCol = cursor.getColumnIndexOrThrow("name")
                val phoneCol = cursor.getColumnIndexOrThrow("phone")
                val qtyCol = cursor.getColumnIndexOrThrow("quantity")
                val rateCol = cursor.getColumnIndexOrThrow("rate")
                val classIdCol = cursor.getColumnIndexOrThrow("classId")
                val createDateCol = cursor.getColumnIndexOrThrow("createDate")
                val updateDateCol = cursor.getColumnIndexOrThrow("updateDate")
                val classNameCol = cursor.getColumnIndexOrThrow("className")

                while (cursor.moveToNext()) {
                    result.add(
                        CustomerDto(
                            id = cursor.getInt(idCol),
                            name = cursor.getString(nameCol) ?: "",
                            phone = cursor.getString(phoneCol),
                            quantity = cursor.getDoubleOrNull(qtyCol),
                            rate = cursor.getDoubleOrNull(rateCol),
                            classId = cursor.getIntOrNull(classIdCol),
                            createDate = cursor.getString(createDateCol),
                            updateDate = cursor.getString(updateDateCol),
                            className = cursor.getString(classNameCol)
                        )
                    )
                }
            }
        } catch (ex: Exception) {
            Log.e("DB", "getAllCustomersList failed", ex)
        }

        return result
    }

    fun searchCustomers(filter: String, classFilterId: Int?): String {
        val out = mutableListOf<Customer>()

        val whereClauses = mutableListOf<String>()
        val argsList = mutableListOf<String>()

        if (filter.isNotBlank()) {
            val wildcard = "%${filter}%"
            whereClauses += "(" +
                    listOf(
                        "CU.id       LIKE ?",
                        "CU.name     LIKE ?",
                        "CU.phone    LIKE ?",
                        "CU.quantity LIKE ?",
                        "CU.rate     LIKE ?"
                    ).joinToString(" OR ") +
                    ")"
            repeat(5) { argsList += wildcard }
        }

        classFilterId?.let {
            whereClauses += "CU.classId = ?"
            argsList += it.toString()
        }

        val whereSection = if (whereClauses.isNotEmpty()) {
            "WHERE " + whereClauses.joinToString(" AND ")
        } else {
            ""
        }

        val sql = """
    SELECT
      CU.id,
      CU.name,
      CU.phone,
      CU.quantity,
      CU.rate,
      CU.classId,
      CU.createDate,
      CU.updateDate,
      CL.name AS className
    FROM Customers CU
    LEFT JOIN Classes CL ON CU.classId = CL.id
    $whereSection
    ORDER BY CU.id ASC
  """.trimIndent()

        return helper.fetchAll(rdb.rawQuery(sql, argsList.toTypedArray())).toString()
    }

    fun getAvailableDailyFinancialSummaries(): List<DailyFinancialsSummary> {
        val db = rdb
        val sql = """
        SELECT batchId, productionDate, totalQtyProduced, totalQtySold, qtyAvailable, totalDailyInputCostAmt, totalDailySalesAmt, unitCostPerLiter,
               totalCogs, netProfitOrLoss
        FROM v_DailyFinancialsSummary
        WHERE qtyAvailable > 0
        ORDER BY productionDate ASC
    """.trimIndent()

        val cursor = db.rawQuery(sql, null)
        val out = mutableListOf<DailyFinancialsSummary>()
        cursor.use {
            while (it.moveToNext()) {
                out += DailyFinancialsSummary(
                    batchId = it.getInt(0),
                    productionDate = it.getString(1),
                    totalQtyProduced = it.getDouble(2),
                    totalQtySold = it.getDouble(3),
                    qtyAvailable = it.getDouble(4),
                    totalDailyInputCostAmt = it.getDouble(5),
                    totalDailySalesAmt = it.getDouble(6),
                    unitCostPerLiter = it.getDouble(7),
                    totalCogs = it.getDouble(8),
                    netProfitOrLoss = it.getDouble(9)
                )
            }
        }
        return out
    }

    fun getCustomerLocations(customerId: Int): String {
        return try {
            val sql =
                """ SELECT  latitude,longitude, capturedAt FROM ${DatabaseHelper.T_CUST_LOCATIONS} WHERE customerId = ? ORDER BY id DESC LIMIT 2 """.trimIndent()

            val cursor = rdb.rawQuery(sql, arrayOf(customerId.toString()))
            val arr = helper.fetchAll(cursor)
            arr.toString()
        } catch (ex: Exception) {
            android.util.Log.e("DB", "getCustomerLocations failed", ex)
            "[]"
        }
    }

    fun deleteCustomerLocation(id: Int) {
        db.execSQL("DELETE FROM ${DatabaseHelper.T_CUST_LOCATIONS} WHERE id = ?", arrayOf(id))
    }

    fun deleteCustomerPhoto(id: Int) {
        db.execSQL("DELETE FROM ${DatabaseHelper.T_CUST_PHOTOS} WHERE id = ?", arrayOf(id))
    }

    fun updateMapUrl(id: Int, url: String) {
        val stmt = db.compileStatement(
            """
        UPDATE ${DatabaseHelper.T_CUST_LOCATIONS}
        SET mapUrl = ?
        WHERE id = ?
    """.trimIndent()
        )
        stmt.bindString(1, url)
        stmt.bindLong(2, id.toLong())
        stmt.executeUpdateDelete()
    }

    fun updateCustomerLatLon(id: Int, lat: Double, lon: Double) {
        val stmt = db.compileStatement(
            """
        UPDATE ${DatabaseHelper.T_CUST_LOCATIONS}
        SET latitude = ?, longitude = ?, capturedAt = datetime('now')
        WHERE id = ?
    """
        )
        stmt.bindDouble(1, lat)
        stmt.bindDouble(2, lon)
        stmt.bindLong(3, id.toLong())
        stmt.executeUpdateDelete()
    }

    fun getCustomerPhotos(customerId: Int): String {
        val cursor = rdb.rawQuery(
            "SELECT imageBlob, createdAt FROM ${DatabaseHelper.T_CUST_PHOTOS} WHERE customerId=? ORDER BY id DESC LIMIT 4",
            arrayOf(customerId.toString())
        )
        val arr = org.json.JSONArray()
        while (cursor.moveToNext()) {
            val blob = cursor.getBlob(0)
            val base64 = android.util.Base64.encodeToString(blob, android.util.Base64.DEFAULT)
            val o = org.json.JSONObject()
            o.put("base64", base64)
            o.put("capturedAt", cursor.getString(1))
            arr.put(o)
        }
        cursor.close()
        return arr.toString()
    }

    fun insertCustomerLocation(customerId: Int, lat: Double, lon: Double, accuracy: Double = 0.00) {
        val stmt = db.compileStatement(
            """
        INSERT INTO ${DatabaseHelper.T_CUST_LOCATIONS}
        (customerId, latitude, longitude, geoAccuracy, capturedAt)
        VALUES (?, ?, ?, ?, datetime('now'))
    """
        )
        stmt.bindLong(1, customerId.toLong())
        stmt.bindDouble(2, lat)
        stmt.bindDouble(3, lon)
        stmt.bindDouble(4, accuracy)
        stmt.executeInsert()
    }

    fun insertCustomerPhoto(customerId: Int, bytes: ByteArray, caption: String) {
        val stmt = db.compileStatement(
            """
        INSERT INTO ${DatabaseHelper.T_CUST_PHOTOS}
        (customerId, imageBlob, caption, createdAt)
        VALUES (?, ?, ?, datetime('now'))
    """
        )
        stmt.bindLong(1, customerId.toLong())
        stmt.bindBlob(2, bytes)
        stmt.bindString(3, caption)
        stmt.executeInsert()
    }

    fun isInvoiceExists(custId: Int, monthId: Int): Boolean {
        val formatter = DateTimeFormatter.ISO_DATE
        val current = YearMonth.now()
        val target = YearMonth.of(current.year, monthId)

        val startDate = target.atDay(1)
        val endDate = if (target == current) LocalDate.now() else target.atEndOfMonth()

        val startDateStr = startDate.format(formatter)
        val endDateStr = endDate.format(formatter)

        val count = helper.fetchScalarInt(

            """
        SELECT COUNT(1)
        FROM sales s
        WHERE s.customerId = ?
          AND date(s.saleDate) BETWEEN ? AND ?
        """.trimIndent(),
            arrayOf(custId.toString(), startDateStr, endDateStr)
        )
        return count > 0
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

            val cursor = rdb.rawQuery(sql, arrayOf(customerId))
            val arr = JSONArray()
            cursor.use { c ->
                while (c.moveToNext()) {
                    val obj = JSONObject()
                    obj.put("id", c.getLong(0))
                    obj.put("receivedAmount", c.getDouble(1))
                    obj.put("applied", c.getDouble(2))
                    obj.put("remaining", c.getDouble(1) - c.getDouble(2))
                    arr.put(obj)
                }
            }
            arr.toString()
        } catch (ex: Exception) {
            Log.e("DB", "getCustomerOpenPayments error: ${ex.message}")
            "[]"
        }
    }

    fun getOpenInvoices(): String {
        return try {
            val sql = """
            SELECT
                I.id, I.customerId, C.name AS customerName,
                I.invoiceDate, I.total, I.status,
                COALESCE(SUM(PA.appliedAmount), 0) AS totalPaid
            FROM invoice I
            LEFT JOIN customers C ON C.id = I.customerId
            LEFT JOIN paymentApplied PA ON PA.appliedToInvoiceId = I.id
            GROUP BY I.id
            HAVING (I.total - COALESCE(SUM(PA.appliedAmount), 0)) > 0.005
            ORDER BY date(I.invoiceDate) ASC
        """.trimIndent()

            val cursor = rdb.rawQuery(sql, null)
            val arr = JSONArray()
            cursor.use { c ->
                while (c.moveToNext()) {
                    val obj = JSONObject()
                    obj.put("id", c.getInt(0))
                    obj.put("customerId", c.getInt(1))
                    obj.put("customerName", c.getString(2) ?: "")
                    obj.put("invoiceDate", c.getString(3) ?: "")
                    obj.put("total", if (!c.isNull(4)) c.getDouble(4) else 0.0)
                    obj.put("status", c.getString(5) ?: "")
                    obj.put("totalPaid", if (!c.isNull(6)) c.getDouble(6) else 0.0)
                    arr.put(obj)
                }
            }
            arr.toString()
        } catch (ex: Exception) {
            Log.e("DatabaseHelper", "getOpenInvoices error: ${ex.message}", ex)
            "[]"
        }
    }

    fun getPaidInvoices(): String {
        return try {
            val sql = """
            SELECT
                I.id, I.customerId, C.name AS customerName,
                I.invoiceDate, I.total, I.status,
                COALESCE(SUM(PA.appliedAmount), 0) AS totalPaid
            FROM invoice I
            LEFT JOIN customers C ON C.id = I.customerId
            LEFT JOIN paymentApplied PA ON PA.appliedToInvoiceId = I.id
            GROUP BY I.id
            HAVING (I.total - COALESCE(SUM(PA.appliedAmount), 0)) <= 0.005
            ORDER BY date(I.invoiceDate) DESC
        """.trimIndent()

            val cursor = rdb.rawQuery(sql, null)
            val arr = JSONArray()
            cursor.use { c ->
                while (c.moveToNext()) {
                    val obj = JSONObject()
                    obj.put("id", c.getInt(0))
                    obj.put("customerId", c.getInt(1))
                    obj.put("customerName", c.getString(2) ?: "")
                    obj.put("invoiceDate", c.getString(3) ?: "")
                    obj.put("total", if (!c.isNull(4)) c.getDouble(4) else 0.0)
                    obj.put("status", c.getString(5) ?: "")
                    obj.put("totalPaid", if (!c.isNull(6)) c.getDouble(6) else 0.0)
                    arr.put(obj)
                }
            }
            arr.toString()
        } catch (ex: Exception) {
            Log.e("DatabaseHelper", "getPaidInvoices error: ${ex.message}", ex)
            "[]"
        }
    }

    fun getInvoiceDetails(invoiceId: Int): String {
        try {
            val hdrQuery = """
            SELECT
                I.id, I.customerId, I.invoiceDate, I.notes, I.total, I.status, C.name,
                COALESCE(SUM(PA.appliedAmount), 0) AS paid,
                (I.total - COALESCE(SUM(PA.appliedAmount), 0)) AS balance
            FROM invoice I
            LEFT JOIN customers C ON C.id = I.customerId
            LEFT JOIN paymentApplied PA ON PA.appliedToInvoiceId = I.id
            WHERE I.id = ?
            GROUP BY I.id
        """.trimIndent()

            val hdrCursor = rdb.rawQuery(hdrQuery, arrayOf(invoiceId.toString()))
            val headerJson = JSONObject()
            hdrCursor.use { c ->
                if (c.moveToFirst()) {
                    headerJson.put("invoiceId", c.getInt(0))
                    headerJson.put("customerId", c.getInt(1))
                    headerJson.put("invoiceDate", c.getString(2))
                    headerJson.put("notes", c.getString(3) ?: "")
                    headerJson.put("total", if (!c.isNull(4)) c.getDouble(4) else 0.0)
                    headerJson.put("status", c.getString(5) ?: "Draft")
                    headerJson.put("customerName", c.getString(6) ?: "")
                    headerJson.put("paid", if (!c.isNull(7)) c.getDouble(7) else 0.0)
                    headerJson.put("balance", if (!c.isNull(8)) c.getDouble(8) else 0.0)
                } else {
                    return JSONObject().put("error", "invoice not found").toString()
                }
            }

            val sql = """
            WITH
              invoiceSales AS (
                SELECT S.*
                FROM sales S
                JOIN invoiceSaleItems I ON S.id = I.saleId
                WHERE I.invoiceId = ?
              ),
              DailyAggregated AS (
                SELECT
                  S.customerId,
                  date(S.saleDate) AS saleDay,
                  SUM(S.quantity) AS quantity,
                  S.rate
                FROM invoiceSales S
                GROUP BY S.customerId, date(S.saleDate), S.rate
              ),
              RankedSales AS (
                SELECT
                  C.name AS customerName,
                  D.saleDay,
                  D.quantity,
                  D.rate,
                  ROW_NUMBER() OVER (
                    PARTITION BY D.quantity ORDER BY D.saleDay
                  ) AS rn,
                  julianday(D.saleDay) AS jd
                FROM DailyAggregated D
                JOIN customers C ON C.id = D.customerId
              ),
              GroupsByDateRange AS (
                SELECT saleDay, quantity, rate, (jd - rn) AS groupKey
                FROM RankedSales
              ),
              Aggregated AS (
                SELECT
                  MIN(saleDay) AS startDate,
                  MAX(saleDay) AS endDate,
                  quantity,
                  SUM(quantity) AS totalQty,
                  SUM(quantity * rate) AS totalAmt
                FROM GroupsByDateRange
                GROUP BY groupKey, quantity
              ),
              DateFormats AS (
                SELECT
                  A.startDate,
                  A.endDate,
                  CAST(julianday(A.endDate) - julianday(A.startDate) + 1 AS TEXT) AS days,
                  strftime('%d', A.startDate) || '-' ||
                  CASE strftime('%m', A.startDate)
                    WHEN '01' THEN 'Jan' WHEN '02' THEN 'Feb' WHEN '03' THEN 'Mar'
                    WHEN '04' THEN 'Apr' WHEN '05' THEN 'May' WHEN '06' THEN 'Jun'
                    WHEN '07' THEN 'Jul' WHEN '08' THEN 'Aug' WHEN '09' THEN 'Sep'
                    WHEN '10' THEN 'Oct' WHEN '11' THEN 'Nov' WHEN '12' THEN 'Dec'
                  END AS fromDay,
                  strftime('%d', A.endDate) || '-' ||
                  CASE strftime('%m', A.endDate)
                    WHEN '01' THEN 'Jan' WHEN '02' THEN 'Feb' WHEN '03' THEN 'Mar'
                    WHEN '04' THEN 'Apr' WHEN '05' THEN 'May' WHEN '06' THEN 'Jun'
                    WHEN '07' THEN 'Jul' WHEN '08' THEN 'Aug' WHEN '09' THEN 'Sep'
                    WHEN '10' THEN 'Oct' WHEN '11' THEN 'Nov' WHEN '12' THEN 'Dec'
                  END AS toDay
                FROM Aggregated A
              )

            SELECT DT.fromDay, DT.toDay, DT.days, A.quantity, A.totalQty, A.totalAmt
            FROM Aggregated A
            JOIN DateFormats DT ON A.startDate = DT.startDate AND A.endDate = DT.endDate
            ORDER BY A.startDate
        """.trimIndent()

            val cur = rdb.rawQuery(sql, arrayOf(invoiceId.toString()))
            val lines = JSONArray()
            cur.use { c ->
                while (c.moveToNext()) {
                    val row = JSONObject()
                    row.put("fromDay", c.getString(0))
                    row.put("toDay", c.getString(1))
                    row.put("days", c.getString(2))
                    row.put("quantity", if (!c.isNull(3)) c.getDouble(3) else 0.0)
                    row.put("totalQty", if (!c.isNull(4)) c.getDouble(4) else 0.0)
                    row.put("totalAmt", if (!c.isNull(5)) c.getDouble(5) else 0.0)
                    lines.put(row)
                }
            }

            val out = JSONObject()
            out.put("header", headerJson)
            out.put("lines", lines)
            return out.toString()

        } catch (ex: Exception) {
            Log.e("DatabaseHelper", "getInvoiceDetails error: ${ex.message}", ex)
            return JSONObject()
                .put("error", ex.message ?: "Unknown error")
                .toString()
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

        rdb.rawQuery(sql, arrayOf(customerId.toString())).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(
                    mapOf(
                        "id" to cursor.getLong(0),
                        "invoiceDate" to cursor.getString(1),
                        "total" to cursor.getDouble(2),
                        "paid" to cursor.getDouble(3),
                        "balance" to cursor.getDouble(4)
                    )
                )
            }
        }
        return list
    }

    private fun getOpenInvoicesWithBalance(customerId: Int): List<InvoiceBalance> {
        val list = mutableListOf<InvoiceBalance>()
        val sql = """
        SELECT i.id, i.total,
               COALESCE(SUM(pa.appliedAmount), 0) AS paid
        FROM invoice i
        LEFT JOIN paymentApplied pa ON pa.appliedToInvoiceId = i.id
        WHERE i.customerId = ?
        GROUP BY i.id
        HAVING (i.total - COALESCE(SUM(pa.appliedAmount), 0)) > 0.005
        ORDER BY i.invoiceDate ASC
    """.trimIndent()

        rdb.rawQuery(sql, arrayOf(customerId.toString())).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val total = cursor.getDouble(1)
                val paid = cursor.getDouble(2)
                list.add(InvoiceBalance(id, total, paid))
            }
        }
        return list
    }

    private fun getOpenPaymentsWithRemaining(customerId: Int): List<PaymentWithRemaining> {
        val list = mutableListOf<PaymentWithRemaining>()
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

        rdb.rawQuery(sql, arrayOf(customerId.toString())).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val received = cursor.getDouble(1)
                val applied = cursor.getDouble(2)
                list.add(PaymentWithRemaining(id, received, applied))
            }
        }
        return list
    }

    @Synchronized
    fun receiveCustomerPayment(customerId: Int, amount: Double, notes: String): Long {
        if (amount <= 0) throw IllegalArgumentException("amount must be > 0")

        try {
            val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

            val paymentValues = ContentValues().apply {
                put("customerId", customerId)
                put("receivedDate", now)
                put("notes", notes)
                put("createDate", now)
                put("updateDate", now)
                put("receivedAmount", amount)
                put("status", "Open")
            }
            val newPaymentId =
                rdb.insertOrThrow("paymentReceived", null, paymentValues)

            val openInvoices = getOpenInvoicesWithBalance(customerId).toMutableList()
            val openPayments = getOpenPaymentsWithRemaining(customerId)

            var invoiceIdx = 0
            for (payment in openPayments) {
                var remainingInPayment = payment.remaining
                if (remainingInPayment <= 0.005) continue

                while (remainingInPayment > 0.005 && invoiceIdx < openInvoices.size) {
                    var invoice = openInvoices[invoiceIdx]
                    var currentInvoiceBalance = invoice.balance

                    if (currentInvoiceBalance <= 0.005) {
                        invoiceIdx++
                        continue
                    }

                    val applyAmt = minOf(remainingInPayment, currentInvoiceBalance)

                    val applyValues = ContentValues().apply {
                        put("paymentReceivedId", payment.id)
                        put("appliedToInvoiceId", invoice.id)
                        put("appliedAmount", applyAmt)
                    }
                    rdb.insertOrThrow("paymentApplied", null, applyValues)

                    invoice.paid += applyAmt

                    remainingInPayment -= applyAmt
                }

                val newStatus = if (remainingInPayment <= 0.005) "Applied" else "Open"
                rdb.execSQL(
                    "UPDATE paymentReceived SET status = ?, updateDate = ? WHERE id = ?",
                    arrayOf(newStatus, now, payment.id)
                )
            }

            for (invoice in openInvoices) {
                val newStatus = if (invoice.balance <= 0.005) "Paid" else "Open"
                rdb.execSQL(
                    "UPDATE invoice SET status = ?, updateDate = ? WHERE id = ?",
                    arrayOf(newStatus, now, invoice.id)
                )
            }

            return newPaymentId
        } catch (e: Exception) {
            throw e
        } finally {
        }
    }

    fun generateCustomerSalesInvoice(customerId: Int, monthId: Int): List<List<String?>> {
        val resultList = mutableListOf<List<String?>>()

        try {
            val formatter = DateTimeFormatter.ISO_DATE

            val current = YearMonth.now()
            val target = YearMonth.of(current.year, monthId)

            val startDate = target.atDay(1)
            val endDate = if (target == current) LocalDate.now() else target.atEndOfMonth()

            val startDateStr = startDate.format(formatter)
            val endDateStr = endDate.format(formatter)

            val sqlQuery = """
            WITH
              DailyAggregated AS (
                SELECT
                  S.customerId,
                  date(S.saleDate) AS saleDay,
                  SUM(S.quantity) AS quantity,
                  S.rate
                FROM sales S
                WHERE
                  S.customerId = ?
                  AND date(S.saleDate) BETWEEN ? AND ?
                GROUP BY
                  date(S.saleDate),
                  S.rate
              ),

              RankedSales AS (
                SELECT
                  C.name AS customerName,
                  D.saleDay,
                  D.quantity,
                  D.rate,
                  ROW_NUMBER() OVER (
                    PARTITION BY D.quantity
                    ORDER BY D.saleDay
                  ) AS rn,
                  julianday(D.saleDay) AS jd
                FROM DailyAggregated D
                JOIN customers C ON C.id = D.customerId
              ),

              GroupsbyDateRange AS (
                SELECT
                  saleDay,
                  quantity,
                  rate,
                  (jd - rn) AS groupKey
                FROM RankedSales
              ),

              Aggregated AS (
                SELECT
                  MIN(saleDay) AS startDate,
                  MAX(saleDay) AS endDate,
                  quantity,
                  SUM(quantity) AS totalQty,
                  SUM(quantity * rate) AS totalAmt
                FROM GroupsbyDateRange
                GROUP BY groupKey, quantity
              ),

              ReportHeader AS (
                SELECT
                  'Milk invoice for ' ||
                  CASE strftime('%m', MIN(startDate))
                    WHEN '01' THEN 'January' WHEN '02' THEN 'February' WHEN '03' THEN 'March'
                    WHEN '04' THEN 'April'   WHEN '05' THEN 'May'      WHEN '06' THEN 'June'
                    WHEN '07' THEN 'July'    WHEN '08' THEN 'August'   WHEN '09' THEN 'September'
                    WHEN '10' THEN 'October' WHEN '11' THEN 'November' WHEN '12' THEN 'December'
                  END || ' ' || strftime('%Y', MIN(startDate)) AS monthYear
                FROM Aggregated
              ),

              DateFormats AS (
                SELECT
                  A.startDate,
                  A.endDate,
                  CAST(julianday(A.endDate) - julianday(A.startDate) + 1 AS TEXT) AS days,
                  strftime('%d', A.startDate) || '-' ||
                    CASE strftime('%m', A.startDate)
                      WHEN '01' THEN 'Jan' WHEN '02' THEN 'Feb' WHEN '03' THEN 'Mar'
                      WHEN '04' THEN 'Apr' WHEN '05' THEN 'May' WHEN '06' THEN 'Jun'
                      WHEN '07' THEN 'Jul' WHEN '08' THEN 'Aug' WHEN '09' THEN 'Sep'
                      WHEN '10' THEN 'Oct' WHEN '11' THEN 'Nov' WHEN '12' THEN 'Dec'
                    END AS fromDay,
                  strftime('%d', A.endDate) || '-' ||
                    CASE strftime('%m', A.endDate)
                      WHEN '01' THEN 'Jan' WHEN '02' THEN 'Feb' WHEN '03' THEN 'Mar'
                      WHEN '04' THEN 'Apr' WHEN '05' THEN 'May' WHEN '06' THEN 'Jun'
                      WHEN '07' THEN 'Jul' WHEN '08' THEN 'Aug' WHEN '09' THEN 'Sep'
                      WHEN '10' THEN 'Oct' WHEN '11' THEN 'Nov' WHEN '12' THEN 'Dec'
                    END AS toDay
                FROM Aggregated A
              )

            SELECT (SELECT monthYear FROM ReportHeader) AS A, NULL AS B, NULL AS C, NULL AS D, NULL AS E, NULL AS F
            UNION ALL
            SELECT 'From Atiq Ur Rehman Contact # 0000 0606700', NULL, NULL, NULL, NULL, NULL
            UNION ALL
            SELECT 'Bill To:', (SELECT customerName FROM RankedSales GROUP BY customerName), NULL, NULL, NULL, NULL
            UNION ALL
            SELECT NULL, NULL, NULL, NULL, NULL, NULL
            UNION ALL
            SELECT 'From','To','Days','Qty','Total Qty','amount'
            UNION ALL
            SELECT DT.fromDay, DT.toDay, DT.days,
                   printf('%.2f', A.quantity),
                   printf('%.2f', A.totalQty),
                   'Rs ' || printf('%,.2f', A.totalAmt)
            FROM Aggregated A
            JOIN DateFormats DT ON A.startDate = DT.startDate AND A.endDate = DT.endDate
            UNION ALL
            SELECT NULL, NULL, NULL, NULL, NULL, NULL
            UNION ALL
            SELECT NULL, NULL, 'Payment Method', NULL, NULL, NULL
            UNION ALL
            SELECT NULL, NULL, 'Bank name: Standard Chartered', NULL, NULL, NULL
            UNION ALL
            SELECT NULL, NULL, 'Account name: ATIQ UR REHMAN', NULL, NULL, NULL
            UNION ALL
            SELECT NULL, NULL, 'IBAN: PK95SCBL0000001502678401', NULL, NULL, NULL;
        """.trimIndent()

            val selectionArgs = arrayOf(
                customerId.toString(),
                startDateStr,
                endDateStr
            )

            val readableDb = rdb
            var cursor: Cursor? = null

            try {
                cursor = readableDb.rawQuery(sqlQuery, selectionArgs)
                val columnCount = cursor.columnCount

                while (cursor.moveToNext()) {
                    val row = mutableListOf<String?>()
                    for (i in 0 until columnCount) {
                        row.add(cursor.getString(i))
                    }
                    resultList.add(row)
                }
            } catch (e: Exception) {
                Log.e("DB", "generateCustomerSalesInvoice failed", e)
            } finally {
                cursor?.close()
            }

        } catch (ex: Exception) {
            Log.e("DB", "generateCustomerSalesInvoice error", ex)
        }

        return resultList
    }

    fun generateCustomerSalesInvoiceString(customerId: Int, monthId: Int): String {
        return try {
            val formatter = DateTimeFormatter.ISO_DATE

            val current = YearMonth.now()
            val target = YearMonth.of(current.year, monthId)

            val startDate = target.atDay(1)
            val endDate = if (target == current) LocalDate.now() else target.atEndOfMonth()

            val sqlQuery = """
            WITH
              DailyAggregated AS (
                SELECT
                  S.customerId,
                  date(S.saleDate) AS saleDay,
                  SUM(S.quantity) AS quantity,
                  S.rate
                FROM sales S
                WHERE
                  S.customerId = ?
                  AND date(S.saleDate) BETWEEN ? AND ?
                GROUP BY S.customerId, date(S.saleDate), S.rate
              ),
              RankedSales AS (
                SELECT
                  C.name AS customerName,
                  D.saleDay,
                  D.quantity,
                  D.rate,
                  ROW_NUMBER() OVER (
                    PARTITION BY D.quantity ORDER BY D.saleDay
                  ) AS rn,
                  julianday(D.saleDay) AS jd
                FROM DailyAggregated D
                JOIN customers C ON C.id = D.customerId
              ),
              GroupsbyDateRange AS (
                SELECT saleDay, quantity, rate, (jd - rn) AS groupKey
                FROM RankedSales
              ),
              Aggregated AS (
                SELECT
                  MIN(saleDay) AS startDate,
                  MAX(saleDay) AS endDate,
                  quantity,
                  SUM(quantity) AS totalQty,
                  SUM(quantity * rate) AS totalAmt
                FROM GroupsbyDateRange
                GROUP BY groupKey, quantity
              ),
              ReportHeader AS (
                SELECT
                  'Milk invoice for ' ||
                  CASE strftime('%m', MIN(startDate))
                    WHEN '01' THEN 'January' WHEN '02' THEN 'February' WHEN '03' THEN 'March'
                    WHEN '04' THEN 'April'   WHEN '05' THEN 'May'      WHEN '06' THEN 'June'
                    WHEN '07' THEN 'July'    WHEN '08' THEN 'August'   WHEN '09' THEN 'September'
                    WHEN '10' THEN 'October' WHEN '11' THEN 'November' WHEN '12' THEN 'December'
                  END || ' ' || strftime('%Y', MIN(startDate)) AS monthYear
                FROM Aggregated
              ),
              DateFormats AS (
                SELECT
                  A.startDate,
                  A.endDate,
                  CAST(julianday(A.endDate) - julianday(A.startDate) + 1 AS TEXT) AS days,
                  strftime('%d', A.startDate) || '-' ||
                  CASE strftime('%m', A.startDate)
                    WHEN '01' THEN 'Jan' WHEN '02' THEN 'Feb' WHEN '03' THEN 'Mar'
                    WHEN '04' THEN 'Apr' WHEN '05' THEN 'May' WHEN '06' THEN 'Jun'
                    WHEN '07' THEN 'Jul' WHEN '08' THEN 'Aug' WHEN '09' THEN 'Sep'
                    WHEN '10' THEN 'Oct' WHEN '11' THEN 'Nov' WHEN '12' THEN 'Dec'
                  END AS fromDay,
                  strftime('%d', A.endDate) || '-' ||
                  CASE strftime('%m', A.endDate)
                    WHEN '01' THEN 'Jan' WHEN '02' THEN 'Feb' WHEN '03' THEN 'Mar'
                    WHEN '04' THEN 'Apr' WHEN '05' THEN 'May' WHEN '06' THEN 'Jun'
                    WHEN '07' THEN 'Jul' WHEN '08' THEN 'Aug' WHEN '09' THEN 'Sep'
                    WHEN '10' THEN 'Oct' WHEN '11' THEN 'Nov' WHEN '12' THEN 'Dec'
                  END AS toDay
                FROM Aggregated A
              )

            SELECT (SELECT monthYear FROM ReportHeader) AS A, NULL AS B, NULL AS C, NULL AS D, NULL AS E, NULL AS F
            UNION ALL
            SELECT 'From Atiq Ur Rehman Contact # 0000 0606700', NULL, NULL, NULL, NULL, NULL
            UNION ALL
            SELECT 'Bill To:', (SELECT customerName FROM RankedSales GROUP BY customerName), NULL, NULL, NULL, NULL
            UNION ALL
            SELECT NULL, NULL, NULL, NULL, NULL, NULL
            UNION ALL
            SELECT 'From','To','Days','Qty','Total Qty','amount'
            UNION ALL
            SELECT DT.fromDay, DT.toDay, DT.days, A.quantity,
                   printf('%.2f', A.totalQty),
                   'Rs ' || printf('%,.2f', A.totalAmt)
            FROM Aggregated A
            JOIN DateFormats DT ON A.startDate = DT.startDate AND A.endDate = DT.endDate
            UNION ALL
            SELECT NULL, NULL, NULL, NULL, NULL, NULL
            UNION ALL
            SELECT NULL, NULL, NULL, 'TOTAL',
                   printf('%.2f', SUM(totalQty)),
                   'Rs ' || printf('%,.2f', SUM(totalAmt))
            FROM Aggregated
            UNION ALL
            SELECT NULL, NULL, NULL, NULL, NULL, NULL
            UNION ALL
            SELECT NULL, NULL, 'Payment Method', NULL, NULL, NULL
            UNION ALL
            SELECT NULL, NULL, 'Bank name: Standard Chartered', NULL, NULL, NULL
            UNION ALL
            SELECT NULL, NULL, 'Account name: ATIQ UR REHMAN', NULL, NULL, NULL
            UNION ALL
            SELECT NULL, NULL, 'IBAN: PK95SCBL0000001502678401', NULL, NULL, NULL;
        """.trimIndent()

            val selectionArgs = arrayOf(
                customerId.toString(),
                startDate.format(formatter),
                endDate.format(formatter)
            )

            val reportJsonArray = JSONArray()
            val readableDb = rdb
            var cursor: Cursor? = null

            try {
                cursor = readableDb.rawQuery(sqlQuery, selectionArgs)
                val columnCount = cursor.columnCount

                while (cursor.moveToNext()) {
                    val rowArray = JSONArray()
                    for (i in 0 until columnCount) {
                        rowArray.put(cursor.getString(i) ?: JSONObject.NULL)
                    }
                    reportJsonArray.put(rowArray)
                }
            } finally {
                cursor?.close()
            }

            JSONObject().apply {
                put("status", "success")
                put("data", reportJsonArray)
            }.toString()

        } catch (ex: Exception) {
            Log.e("DB", "generateCustomerSalesinvoice failed", ex)
            JSONObject().apply {
                put("status", "error")
                put("message", ex.message ?: "Unknown database error")
            }.toString()
        }
    }
}
