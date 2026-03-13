package com.example.dairypos.data.repository.operational

import android.util.Log
import com.example.dairypos.DatabaseHelper
import com.example.dairypos.data.repository.contracts.IAccountingEngine
import com.example.dairypos.data.repository.contracts.OperationalEvent
import org.json.JSONObject

/** Operational — fuel expense recording. Triggers Expense/Fuel journal entries. */
class r9_FuelExpenseRepository(
    private val helper: DatabaseHelper,
    private val engine: IAccountingEngine
) {
    private val db  get() = helper.writableDatabase
    private val rdb get() = helper.readableDatabase

    fun saveFuelExpense(json: String): String {
        return try {
            val obj = JSONObject(json)
            engine.processEvent(OperationalEvent(
                transactionTypeName = "Expense",
                subType             = "Fuel",
                tableName           = DatabaseHelper.T_JOURNAL,
                entityId            = 0,
                amount              = obj.getDouble("amount"),
                notes               = obj.optString("notes", "Fuel expense")
            ))
            JSONObject().put("status", "success").toString()
        } catch (ex: Exception) {
            Log.e("r9Fuel", "saveFuelExpense failed", ex)
            JSONObject().put("error", ex.message ?: "unknown").toString()
        }
    }
}
