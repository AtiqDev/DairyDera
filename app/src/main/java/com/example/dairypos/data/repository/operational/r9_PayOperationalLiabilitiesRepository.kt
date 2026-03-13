package com.example.dairypos.data.repository.operational

import android.content.ContentValues
import android.util.Log
import com.example.dairypos.DatabaseHelper
import com.example.dairypos.data.repository.contracts.IAccountingEngine
import com.example.dairypos.data.repository.contracts.OperationalEvent
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Operational — operational liability staging, daily accrual, and payment settlement. */
class r9_PayOperationalLiabilitiesRepository(
    private val helper: DatabaseHelper,
    private val engine: IAccountingEngine
) {
    private val db  get() = helper.writableDatabase
    private val rdb get() = helper.readableDatabase

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
            val obj           = JSONObject(json)
            val subType       = obj.getString("subType")
            val amount        = obj.getDouble("amount")
            val date          = obj.getString("paymentDate")
            val notes         = obj.optString("notes", "")
            val paymentMethod = obj.optString("paymentMethod", "Cash")
            val effectiveSubType = if (paymentMethod == "Bank") "${subType}_Bank" else subType

            val id = engine.processEvent(OperationalEvent(
                transactionTypeName = "PayableSettlement",
                subType             = effectiveSubType,
                tableName           = "operationalPayments",
                entityId            = helper.getNextSequence(),
                amount              = amount,
                date                = date,
                notes               = notes
            ))
            JSONObject().put("id", id).toString()
        } catch (ex: Exception) {
            JSONObject().put("error", ex.message).toString()
        }
    }

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
                    entities.add(EntityInfo(
                        id           = cur.getInt(cur.getColumnIndexOrThrow("id")),
                        tableName    = cur.getString(cur.getColumnIndexOrThrow("TableName")),
                        monetaryCol  = cur.getString(cur.getColumnIndexOrThrow("MonetaryColumnName"))
                    ))
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
                        put("entityId",    entity.id)
                        put("period",      currentPeriod)
                        put("monthlyCost", sum)
                        put("updatedOn",   System.currentTimeMillis())
                    }
                )
            }
        } catch (ex: Exception) {
            Log.e("r9PayLiabilities", "stageMonthlyOperationalCosts failed", ex)
        }
    }

    fun processOperationalCostExpenses() {
        try {
            val today         = LocalDate.now()
            val dateStr       = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
            val currentPeriod = today.format(DateTimeFormatter.ofPattern("yyyy-MM"))
            val daysInMonth   = today.lengthOfMonth()

            data class Candidate(val entityId: Int, val entityName: String, val monthlyCost: Double)
            val candidates = mutableListOf<Candidate>()

            val sql = """
                SELECT oe.id AS entityId, oe.EntityName, moc.monthlyCost
                  FROM ${DatabaseHelper.T_OPERATIONAL_ENTITIES} oe
                  JOIN ${DatabaseHelper.T_MONTHLY_OP_COSTS} moc ON moc.entityId = oe.id AND moc.period = ?
                  JOIN modulesRegistry m ON oe.moduleId = m.id
                  JOIN transactionTypes tt ON tt.moduleId = m.id AND tt.name = 'Expense'
                  JOIN accountingJournalsMap ajm ON ajm.transactionTypeId = tt.id AND LOWER(ajm.subType) = LOWER(oe.EntityName)
                 WHERE oe.moduleId IS NOT NULL
                   AND NOT EXISTS (
                       SELECT 1 FROM ${DatabaseHelper.T_DAILY_OP_EXPENSES}
                        WHERE entityId = oe.id AND expenseDate = ?
                   )
            """.trimIndent()

            rdb.rawQuery(sql, arrayOf(currentPeriod, dateStr)).use { cur ->
                while (cur.moveToNext()) {
                    candidates.add(Candidate(
                        entityId   = cur.getInt(cur.getColumnIndexOrThrow("entityId")),
                        entityName = cur.getString(cur.getColumnIndexOrThrow("EntityName")),
                        monthlyCost = cur.getDouble(cur.getColumnIndexOrThrow("monthlyCost"))
                    ))
                }
            }

            candidates.forEach { c ->
                val dailyAmt = BigDecimal(c.monthlyCost)
                    .divide(BigDecimal(daysInMonth), 4, RoundingMode.HALF_UP)
                    .toDouble()
                if (dailyAmt <= 0.0) return@forEach

                val journalRefId = engine.processEvent(OperationalEvent(
                    transactionTypeName = "Expense",
                    subType             = c.entityName,
                    tableName           = DatabaseHelper.T_OPERATIONAL_ENTITIES,
                    entityId            = c.entityId,
                    amount              = dailyAmt,
                    date                = dateStr,
                    notes               = "Auto daily ${c.entityName} expense for $dateStr"
                ))

                db.insert(
                    DatabaseHelper.T_DAILY_OP_EXPENSES, null,
                    ContentValues().apply {
                        put("entityId",     c.entityId)
                        put("expenseDate",  dateStr)
                        put("amount",       dailyAmt)
                        put("journalRefId", journalRefId)
                        put("updatedOn",    System.currentTimeMillis())
                    }
                )
            }
        } catch (ex: Exception) {
            Log.e("r9PayLiabilities", "processOperationalCostExpenses failed", ex)
        }
    }
}
