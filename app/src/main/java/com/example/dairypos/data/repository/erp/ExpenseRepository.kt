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
import java.math.BigDecimal
import java.math.RoundingMode

class ExpenseRepository(private val helper: DatabaseHelper) {
    private val db get() = helper.writableDatabase
    private val rdb get() = helper.readableDatabase

    fun getWorkers(): String {
        val sql = "SELECT id, name FROM ${DatabaseHelper.T_EMPLOYEES} ORDER BY name"
        val cursor = rdb.rawQuery(sql, null)
        val arr = JSONArray()

        cursor.use {
            while (it.moveToNext()) {
                val obj = JSONObject().apply {
                    put("id", it.getInt(it.getColumnIndexOrThrow("id")))
                    put("name", it.getString(it.getColumnIndexOrThrow("name")))
                }
                arr.put(obj)
            }
        }

        return JSONObject().apply {
            put("workers", arr)
        }.toString()
    }

    fun insertDailyLaborWage(
        workerId: Int,
        wageDate: String,
        amount: Double,
        notes: String
    ): Int {
        val db = this.db
        val cv = ContentValues().apply {
            put("workerId", workerId)
            put("wageDate", wageDate)
            put("amount", amount)
            put("notes", notes)
            put("updateDate", helper.nowIso())
        }
        val updated = db.update(
            DatabaseHelper.T_DAILY_LABOR_WAGES, cv,
            "workerId = ? AND wageDate = ?",
            arrayOf(workerId.toString(), wageDate)
        )
        if (updated > 0) {
            db.query(
                DatabaseHelper.T_DAILY_LABOR_WAGES, arrayOf("id"),
                "workerId = ? AND wageDate = ?",
                arrayOf(workerId.toString(), wageDate),
                null, null, null
            ).use { c ->
                if (c.moveToFirst()) {
                    return c.getInt(c.getColumnIndexOrThrow("id"))
                }
            }
        }
        return db.insert(DatabaseHelper.T_DAILY_LABOR_WAGES, null, cv).toInt()
    }

    fun processDailyWages(forDate: String? = null) {
        try {
            val dailyWages = helper.getDailyLaborExpenses()
            if (dailyWages.isNotEmpty()) {
                Log.i("LaborProcessor", "No daily labor expenses found for processing.")
                return;
            }
            val db = this.db

            val date = forDate
                ?.let { LocalDate.parse(it) }
                ?: LocalDate.now()
            val fmt = DateTimeFormatter.ISO_LOCAL_DATE
            val dateStr = date.format(fmt)
            val daysInMon = date.lengthOfMonth()

            val cursor = db.query(
                DatabaseHelper.T_EMPLOYEES,
                arrayOf("id", "salary"),
                null, null, null, null, null
            )

            var count = 0
            cursor.use { cur ->
                val idxId = cur.getColumnIndexOrThrow("id")
                val idxSalary = cur.getColumnIndexOrThrow("salary")

                while (cur.moveToNext()) {
                    val workerId = cur.getInt(idxId)
                    val salary = cur.getDouble(idxSalary)
                    val dailyAmt = BigDecimal(salary)
                        .divide(BigDecimal(daysInMon), 2, RoundingMode.HALF_UP)
                        .toDouble()

                    val notes = "Auto daily wage for $dateStr"
                    val wageId = insertDailyLaborWage(workerId, dateStr, dailyAmt, notes)

                    val expenseInput = AccountingTransactionInput(
                        type = "Expense",
                        subType = "Wages",
                        table = "dailyLaborWages",
                        refId = wageId,
                        refId2 = 0,
                        productId = 0,
                        amount = dailyAmt,
                        unitPrice = 0.00,
                        date = null,
                        notes = null
                    )
                    helper.insertAccountingTransactionAndPostJournal(expenseInput)
                    count++
                }
            }
        } catch (ex: Exception) {
            android.util.Log.e("DB", "processDailyLaborWages failed", ex)
        }
    }

    fun insertDailyRentalAllocation(leaseId: Int, allocationDate: String, dailyAmt: Double, notes: String): Pair<Int, Boolean> {
        val db = this.db
        val tableName = "dailyRentalAllocation"
        val cv = ContentValues().apply {
            put("leaseId", leaseId)
            put("allocationDate", allocationDate)
            put("amount", dailyAmt)
            put("notes", notes)
        }

        val idQuery = "SELECT id FROM $tableName WHERE leaseId = ? AND allocationDate = ?"
        val existingId = helper.fetchScalarInt(idQuery, arrayOf(leaseId.toString(), allocationDate))

        if (existingId != 0) {
            db.update(tableName, cv, "id = ?", arrayOf(existingId.toString()))
            return Pair(existingId, false)
        } else {
            val newId = db.insert(tableName, null, cv).toInt()
            if (newId == -1) {
                throw Exception("Failed to insert daily rental allocation.")
            }
            return Pair(newId, true)
        }
    }

    fun processDailyRentalCost(forDate: String? = null) {
        try {
            val db = this.db

            val date = forDate
                ?.let { LocalDate.parse(it) }
                ?: LocalDate.now()
            val fmt = DateTimeFormatter.ISO_LOCAL_DATE
            val dateStr = date.format(fmt)
            val daysInMon = date.lengthOfMonth()

            val cursor = db.query(
                DatabaseHelper.T_LEASES,
                arrayOf("id", "propertyName", "baseRent", "startDate", "escalationRate", "escalationIntervalMonths"),
                "endDate IS NULL OR endDate >= ?",
                arrayOf(dateStr),
                null, null, null
            )

            var count = 0
            cursor.use { cur ->
                val idxId = cur.getColumnIndexOrThrow("id")
                val idxName = cur.getColumnIndexOrThrow("propertyName")
                val idxBaseRent = cur.getColumnIndexOrThrow("baseRent")

                while (cur.moveToNext()) {
                    val leaseId = cur.getInt(idxId)
                    val propertyName = cur.getString(idxName)
                    val baseRent = cur.getDouble(idxBaseRent)

                    val dailyAmt = BigDecimal(baseRent)
                        .divide(BigDecimal(daysInMon), 4, RoundingMode.HALF_UP)
                        .toDouble()

                    val notes = "Daily rent allocation for $propertyName on $dateStr"
                    val (allocationId, isNewEntry) = insertDailyRentalAllocation(
                        leaseId = leaseId,
                        allocationDate = dateStr,
                        dailyAmt = dailyAmt,
                        notes = notes
                    )

                    if (!isNewEntry) continue

                    val expenseInput = AccountingTransactionInput(
                        type = "Expense",
                        subType = "Rent",
                        table = DatabaseHelper.T_DAILY_RENTAL_ALLOCATION,
                        refId = allocationId,
                        refId2 = leaseId,
                        productId = 0,
                        amount = dailyAmt,
                        unitPrice = 0.00,
                        date = dateStr,
                        notes = notes
                    )
                    helper.insertAccountingTransactionAndPostJournal(expenseInput)
                    count++
                }
            }
        } catch (ex: Exception) {
            android.util.Log.e("DB", "processDailyRentalCost failed", ex)
        }
    }

    fun saveLaborExpense(json: String): String {
        return try {
            val obj = JSONObject(json)
            val amt = obj.getDouble("amount")
            val notes = obj.optString("notes", "Labor expense")

            val expenseInput = AccountingTransactionInput(
                type = "Expense",
                subType = "Wages",
                table = DatabaseHelper.T_JOURNAL,
                refId = 0,
                refId2 = 0,
                productId = 0,
                amount = amt,
                unitPrice = 0.00,
                date = null,
                notes = notes
            )
            helper.insertAccountingTransactionAndPostJournal(expenseInput)
            JSONObject().put("status", "success").toString()
        } catch (ex: Exception) {
            android.util.Log.e("DB", "saveLaborExpense failed", ex)
            JSONObject().put("error", ex.message ?: "unknown").toString()
        }
    }

    fun saveFuelExpense(json: String): String {
        return try {
            val obj = JSONObject(json)
            val amount = obj.getDouble("amount")
            val notes = obj.optString("notes", "Fuel expense")

            val fuelInput = AccountingTransactionInput(
                type = "Expense",
                subType = "Fuel",
                table = DatabaseHelper.T_JOURNAL,
                refId = 0,
                refId2 = 0,
                productId = 0,
                amount = amount,
                unitPrice = null,
                date = null,
                notes = notes
            )
            helper.insertAccountingTransactionAndPostJournal(fuelInput)
            JSONObject().put("status", "success").toString()
        } catch (ex: Exception) {
            android.util.Log.e("DB", "saveFuelExpense failed", ex)
            JSONObject().put("error", ex.message ?: "unknown").toString()
        }
    }
}
