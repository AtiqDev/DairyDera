package com.example.dairypos.data.repository.erp

import android.content.ContentValues
import android.util.Log
import com.example.dairypos.DatabaseHelper
import com.example.dairypos.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class ExpenseRepository(private val helper: DatabaseHelper) {
    private val db get() = helper.writableDatabase
    private val rdb get() = helper.readableDatabase

    fun getWorkers(): String {
        val sql = "SELECT id, name FROM ${DatabaseHelper.T_EMPLOYEES} ORDER BY name"
        val arr = JSONArray()
        rdb.rawQuery(sql, null).use {
            while (it.moveToNext()) {
                arr.put(
                    JSONObject()
                        .put("id", it.getInt(it.getColumnIndexOrThrow("id")))
                        .put("name", it.getString(it.getColumnIndexOrThrow("name")))
                )
            }
        }
        return JSONObject().put("workers", arr).toString()
    }

    // ─── Monthly staging (once-per-month idempotent) ──────────────────────────

    fun stageMonthlyOperationalCosts() {
        try {
            val currentPeriod = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"))

            data class EntityInfo(val id: Int, val tableName: String, val monetaryCol: String)
            val entities = mutableListOf<EntityInfo>()

            rdb.rawQuery(
                "SELECT id, TableName, MonetaryColumnName FROM ${DatabaseHelper.T_OPERATIONAL_ENTITIES} WHERE moduleId IS NOT NULL",
                null
            ).use { cur ->
                while (cur.moveToNext()) {
                    entities.add(
                        EntityInfo(
                            id = cur.getInt(cur.getColumnIndexOrThrow("id")),
                            tableName = cur.getString(cur.getColumnIndexOrThrow("TableName")),
                            monetaryCol = cur.getString(cur.getColumnIndexOrThrow("MonetaryColumnName"))
                        )
                    )
                }
            }

            entities.forEach { entity ->
                val alreadyStaged = helper.fetchScalarInt(
                    "SELECT 1 FROM ${DatabaseHelper.T_MONTHLY_OP_COSTS} WHERE entityId=? AND period=?",
                    arrayOf(entity.id.toString(), currentPeriod)
                ) > 0
                if (alreadyStaged) return@forEach

                // TableName and MonetaryColumnName are seeded by us — not user input
                val sum = helper.fetchScalarDouble(
                    "SELECT COALESCE(SUM(${entity.monetaryCol}), 0) FROM ${entity.tableName}"
                )

                db.insert(
                    DatabaseHelper.T_MONTHLY_OP_COSTS, null,
                    ContentValues().apply {
                        put("entityId", entity.id)
                        put("period", currentPeriod)
                        put("monthlyCost", sum)
                    }
                )
            }
        } catch (ex: Exception) {
            Log.e("ExpenseRepo", "stageMonthlyOperationalCosts failed", ex)
        }
    }

    // ─── Daily expense accrual (daily idempotent) ─────────────────────────────

    fun processOperationalCostExpenses() {
        try {
            val today = LocalDate.now()
            val dateStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
            val currentPeriod = today.format(DateTimeFormatter.ofPattern("yyyy-MM"))
            val daysInMonth = today.lengthOfMonth()

            data class Candidate(val entityId: Int, val entityName: String, val monthlyCost: Double)
            val candidates = mutableListOf<Candidate>()

            val sql = """
                SELECT oe.id AS entityId, oe.EntityName, moc.monthlyCost
                  FROM ${DatabaseHelper.T_OPERATIONAL_ENTITIES} oe
                  JOIN ${DatabaseHelper.T_MONTHLY_OP_COSTS} moc
                    ON moc.entityId = oe.id AND moc.period = ?
                  JOIN modulesRegistry m ON oe.moduleId = m.id
                  JOIN transactionTypes tt ON tt.moduleId = m.id AND tt.name = 'Expense'
                  JOIN accountingJournalsMap ajm
                    ON ajm.transactionTypeId = tt.id
                   AND LOWER(ajm.subType) = LOWER(oe.EntityName)
                 WHERE oe.moduleId IS NOT NULL
                   AND NOT EXISTS (
                       SELECT 1 FROM ${DatabaseHelper.T_DAILY_OP_EXPENSES}
                        WHERE entityId = oe.id AND expenseDate = ?
                   )
            """.trimIndent()

            rdb.rawQuery(sql, arrayOf(currentPeriod, dateStr)).use { cur ->
                while (cur.moveToNext()) {
                    candidates.add(
                        Candidate(
                            entityId = cur.getInt(cur.getColumnIndexOrThrow("entityId")),
                            entityName = cur.getString(cur.getColumnIndexOrThrow("EntityName")),
                            monthlyCost = cur.getDouble(cur.getColumnIndexOrThrow("monthlyCost"))
                        )
                    )
                }
            }

            candidates.forEach { c ->
                val dailyAmt = BigDecimal(c.monthlyCost)
                    .divide(BigDecimal(daysInMonth), 4, RoundingMode.HALF_UP)
                    .toDouble()
                if (dailyAmt <= 0.0) return@forEach

                val input = AccountingTransactionInput(
                    type = "Expense",
                    subType = c.entityName,
                    table = DatabaseHelper.T_OPERATIONAL_ENTITIES,
                    refId = c.entityId,
                    refId2 = 0,
                    productId = 0,
                    amount = dailyAmt,
                    unitPrice = 0.0,
                    date = dateStr,
                    notes = "Auto daily ${c.entityName} expense for $dateStr"
                )
                val journalRefId = helper.insertAccountingTransactionAndPostJournal(input)

                db.insert(
                    DatabaseHelper.T_DAILY_OP_EXPENSES, null,
                    ContentValues().apply {
                        put("entityId", c.entityId)
                        put("expenseDate", dateStr)
                        put("amount", dailyAmt)
                        put("journalRefId", journalRefId)
                    }
                )
            }
        } catch (ex: Exception) {
            Log.e("ExpenseRepo", "processOperationalCostExpenses failed", ex)
        }
    }

    // ─── Manual expense saves (kept for direct-entry screens) ────────────────

    fun saveLaborExpense(json: String): String {
        return try {
            val obj = JSONObject(json)
            val input = AccountingTransactionInput(
                type = "Expense", subType = "Wages",
                table = DatabaseHelper.T_JOURNAL, refId = 0, refId2 = 0,
                productId = 0, amount = obj.getDouble("amount"),
                unitPrice = 0.0, date = null,
                notes = obj.optString("notes", "Labor expense")
            )
            helper.insertAccountingTransactionAndPostJournal(input)
            JSONObject().put("status", "success").toString()
        } catch (ex: Exception) {
            Log.e("ExpenseRepo", "saveLaborExpense failed", ex)
            JSONObject().put("error", ex.message ?: "unknown").toString()
        }
    }

    fun saveFuelExpense(json: String): String {
        return try {
            val obj = JSONObject(json)
            val input = AccountingTransactionInput(
                type = "Expense", subType = "Fuel",
                table = DatabaseHelper.T_JOURNAL, refId = 0, refId2 = 0,
                productId = 0, amount = obj.getDouble("amount"),
                unitPrice = null, date = null,
                notes = obj.optString("notes", "Fuel expense")
            )
            helper.insertAccountingTransactionAndPostJournal(input)
            JSONObject().put("status", "success").toString()
        } catch (ex: Exception) {
            Log.e("ExpenseRepo", "saveFuelExpense failed", ex)
            JSONObject().put("error", ex.message ?: "unknown").toString()
        }
    }

    fun getOperationalPayableBalances(): String {
        val sql = """
            SELECT ca.code, ca.name,
              ROUND(
                IFNULL(SUM(CASE WHEN je.creditAccountId = ca.id THEN je.amount ELSE 0 END), 0) -
                IFNULL(SUM(CASE WHEN je.debitAccountId  = ca.id THEN je.amount ELSE 0 END), 0)
              , 2) AS balance
            FROM chartAccounts ca
            LEFT JOIN journalEntries je ON je.creditAccountId = ca.id OR je.debitAccountId = ca.id
            WHERE ca.code IN ('2001','2002','2003','2004')
            GROUP BY ca.id
            ORDER BY ca.code
        """.trimIndent()
        return helper.fetchAll(rdb.rawQuery(sql, null)).toString()
    }

    fun saveOperationalPayment(json: String): String {
        return try {
            val obj     = JSONObject(json)
            val subType = obj.getString("subType")
            val amount  = obj.getDouble("amount")
            val date    = obj.getString("paymentDate")
            val notes   = obj.optString("notes", "")

            val input = AccountingTransactionInput(
                type      = "PayableSettlement",
                subType   = subType,
                table     = "operationalPayments",
                refId     = helper.getNextSequence(),
                refId2    = 0,
                productId = 0,
                amount    = amount,
                unitPrice = null,
                date      = date,
                notes     = notes
            )
            val id = helper.insertAccountingTransactionAndPostJournal(input)
            JSONObject().put("id", id).toString()
        } catch (ex: Exception) {
            JSONObject().put("error", ex.message).toString()
        }
    }
}
