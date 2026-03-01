package com.example.dairypos.data.repository.accounting

import android.content.ContentValues
import android.util.Log
import com.example.dairypos.DatabaseHelper
import org.json.JSONObject

class ModulesRepository(private val helper: DatabaseHelper) {
    private val db  get() = helper.writableDatabase
    private val rdb get() = helper.readableDatabase

    fun getModules(): String =
        helper.fetchAll(
            rdb.rawQuery(
                """
                SELECT m.id, m.name,
                       COUNT(tt.id)                                   AS txnTypeCount,
                       COUNT(CASE WHEN ajm.id IS NOT NULL THEN 1 END) AS mappedCount
                FROM modulesRegistry m
                LEFT JOIN TransactionTypes tt ON tt.moduleId = m.id
                LEFT JOIN AccountingJournalsMap ajm ON ajm.transactionTypeId = tt.id
                GROUP BY m.id, m.name
                ORDER BY m.id
                """.trimIndent(),
                null
            )
        ).toString()

    fun getModuleDetail(moduleId: Int): String =
        helper.fetchAll(
            rdb.rawQuery(
                """
                SELECT tt.id AS txnTypeId, tt.name AS txnTypeName, COUNT(ajm.id) AS mappingCount
                FROM TransactionTypes tt
                LEFT JOIN AccountingJournalsMap ajm ON ajm.transactionTypeId = tt.id
                WHERE tt.moduleId = ?
                GROUP BY tt.id, tt.name
                ORDER BY tt.name
                """.trimIndent(),
                arrayOf(moduleId.toString())
            )
        ).toString()

    fun getMappingsForTxnType(txnTypeId: Int): String =
        helper.fetchAll(
            rdb.rawQuery(
                """
                SELECT ajm.id, ajm.transactionTypeId, ajm.subType,
                       ajm.debitAccountId,  da.code AS debitCode,  da.name AS debitName,
                       ajm.creditAccountId, ca.code AS creditCode, ca.name AS creditName,
                       ajm.sequence, ajm.condition
                FROM AccountingJournalsMap ajm
                JOIN ChartAccounts da ON da.id = ajm.debitAccountId
                JOIN ChartAccounts ca ON ca.id = ajm.creditAccountId
                WHERE ajm.transactionTypeId = ?
                ORDER BY ajm.sequence, ajm.subType
                """.trimIndent(),
                arrayOf(txnTypeId.toString())
            )
        ).toString()

    fun saveMapping(json: String): String {
        return try {
            val obj = JSONObject(json)
            val id  = obj.optInt("id", 0)
            val cv  = ContentValues().apply {
                put("transactionTypeId", obj.getInt("transactionTypeId"))
                val subType = obj.optString("subType").ifBlank { null }
                if (subType != null) put("subType", subType) else putNull("subType")
                put("debitAccountId",  obj.getInt("debitAccountId"))
                put("creditAccountId", obj.getInt("creditAccountId"))
                put("sequence",        obj.optInt("sequence", 1))
                val condition = obj.optString("condition").ifBlank { null }
                if (condition != null) put("condition", condition) else putNull("condition")
            }
            val finalId = if (id > 0) {
                db.update("AccountingJournalsMap", cv, "id = ?", arrayOf(id.toString()))
                id
            } else {
                db.insert("AccountingJournalsMap", null, cv).toInt()
            }
            JSONObject().put("id", finalId).toString()
        } catch (e: Exception) {
            Log.e("ModulesRepo", "saveMapping failed", e)
            JSONObject().put("error", e.message ?: "Unknown error").toString()
        }
    }

    fun deleteMapping(id: Int): String {
        val rows = db.delete("AccountingJournalsMap", "id = ?", arrayOf(id.toString()))
        return JSONObject().put("deleted", rows).toString()
    }

    fun getUnmappedSummary(): String =
        helper.fetchAll(
            rdb.rawQuery(
                """
                SELECT m.name AS moduleName, tt.id AS txnTypeId, tt.name AS txnTypeName
                FROM TransactionTypes tt
                JOIN modulesRegistry m ON m.id = tt.moduleId
                WHERE NOT EXISTS (
                    SELECT 1 FROM AccountingJournalsMap ajm WHERE ajm.transactionTypeId = tt.id
                )
                ORDER BY m.name, tt.name
                """.trimIndent(),
                null
            )
        ).toString()
}
