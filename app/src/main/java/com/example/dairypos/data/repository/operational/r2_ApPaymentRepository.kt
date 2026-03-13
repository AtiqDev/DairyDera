package com.example.dairypos.data.repository.operational

import android.content.ContentValues
import android.util.Log
import com.example.dairypos.DatabaseHelper
import com.example.dairypos.data.repository.contracts.IAccountingEngine
import com.example.dairypos.data.repository.contracts.OperationalEvent
import org.json.JSONObject

/** Operational — AP payment recording. Triggers PayablePayment journal entries. */
class r2_ApPaymentRepository(
    private val helper: DatabaseHelper,
    private val engine: IAccountingEngine
) {
    private val db  get() = helper.writableDatabase
    private val rdb get() = helper.readableDatabase

    fun savePayablePayment(json: String): String {
        return try {
            val obj             = JSONObject(json)
            val supplierId      = obj.getInt("supplierId")
            val amount          = obj.getDouble("amount")
            val paymentMethodId = obj.getInt("paymentMethodId")
            val notes           = obj.optString("notes", "")
            val date            = obj.optString("paymentDate", helper.nowISoDateOnly())

            val methodName = rdb.rawQuery(
                "SELECT name FROM apPaymentMethods WHERE id = ?",
                arrayOf(paymentMethodId.toString())
            ).use { c ->
                if (c.moveToFirst()) c.getString(0)
                else throw IllegalArgumentException("Unknown paymentMethodId: $paymentMethodId")
            }

            val cv = ContentValues().apply {
                put("supplierId",      supplierId)
                put("paymentDate",     date)
                put("amount",          amount)
                put("paymentMethodId", paymentMethodId)
                put("notes",           notes)
                put("createDate",      helper.nowIso())
                put("updatedOn",       System.currentTimeMillis())
            }
            val paymentId = db.insert("payablePayment", null, cv).toInt()

            engine.processEvent(OperationalEvent(
                transactionTypeName = "PayablePayment",
                subType             = methodName,
                tableName           = "payablePayment",
                entityId            = paymentId,
                amount              = amount,
                date                = date,
                notes               = notes.ifBlank { null }
            ))

            JSONObject().put("id", paymentId).put("isGood", true).toString()
        } catch (e: Exception) {
            Log.e("r2ApPayment", "savePayablePayment failed", e)
            JSONObject().put("error", e.message ?: "Unknown").toString()
        }
    }
}
