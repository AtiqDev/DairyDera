package com.example.dairypos.data.repository.operational

import android.util.Log
import com.example.dairypos.DatabaseHelper
import com.example.dairypos.data.repository.contracts.IAccountingEngine
import com.example.dairypos.data.repository.contracts.OperationalEvent
import org.json.JSONArray
import org.json.JSONObject

/** Operational — worker/labour expense recording. Triggers Expense/Wages journal entries. */
class r9_WorkerExpenseRepository(
    private val helper: DatabaseHelper,
    private val engine: IAccountingEngine
) {
    private val db  get() = helper.writableDatabase
    private val rdb get() = helper.readableDatabase

    fun getWorkers(): String {
        val sql = "SELECT id, name FROM ${DatabaseHelper.T_EMPLOYEES} ORDER BY name"
        val arr = JSONArray()
        rdb.rawQuery(sql, null).use {
            while (it.moveToNext()) {
                arr.put(
                    JSONObject()
                        .put("id",   it.getInt(it.getColumnIndexOrThrow("id")))
                        .put("name", it.getString(it.getColumnIndexOrThrow("name")))
                )
            }
        }
        return JSONObject().put("workers", arr).toString()
    }

    fun saveLaborExpense(json: String): String {
        return try {
            val obj = JSONObject(json)
            engine.processEvent(OperationalEvent(
                transactionTypeName = "Expense",
                subType             = "Wages",
                tableName           = DatabaseHelper.T_JOURNAL,
                entityId            = 0,
                amount              = obj.getDouble("amount"),
                notes               = obj.optString("notes", "Labor expense")
            ))
            JSONObject().put("status", "success").toString()
        } catch (ex: Exception) {
            Log.e("r9Worker", "saveLaborExpense failed", ex)
            JSONObject().put("error", ex.message ?: "unknown").toString()
        }
    }
}
