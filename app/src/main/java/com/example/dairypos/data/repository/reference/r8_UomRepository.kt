package com.example.dairypos.data.repository.reference

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.example.dairypos.DatabaseHelper
import org.json.JSONObject

/** Reference data for units of measure and unit conversions. No accounting. */
class r8_UomRepository(private val helper: DatabaseHelper) {
    private val db  get() = helper.writableDatabase
    private val rdb get() = helper.readableDatabase

    fun getAllUnits(): String =
        helper.fetchAll(rdb.rawQuery("SELECT * FROM ${DatabaseHelper.T_UOMS}", null)).toString()

    fun getUOMList(): String =
        helper.fetchAll(rdb.rawQuery("SELECT id, name FROM ${DatabaseHelper.T_UOMS}", null)).toString()

    fun saveUnit(json: String): String {
        val obj = JSONObject(json)
        val id = obj.optInt("id", 0)
        val cv = ContentValues().apply {
            put("name", obj.getString("name"))
            put("type", obj.getString("type"))
        }
        val newId = if (id > 0) {
            db.update(DatabaseHelper.T_UOMS, cv, "id=?", arrayOf(id.toString()))
            id
        } else {
            db.insert(DatabaseHelper.T_UOMS, null, cv).toInt()
        }
        return JSONObject().put("id", newId).toString()
    }

    fun deleteUnit(id: Int): String {
        val rowsDeleted = db.delete(DatabaseHelper.T_UOMS, "id = ?", arrayOf(id.toString()))
        return JSONObject().put("deleted", rowsDeleted).toString()
    }

    fun getUomId(db: SQLiteDatabase, name: String): Long? {
        db.query("uoms", arrayOf("id"), "name = ?", arrayOf(name), null, null, null).use { c ->
            return if (c.moveToFirst()) c.getLong(c.getColumnIndexOrThrow("id")) else null
        }
    }

    fun getAllConversions(): String =
        helper.fetchAll(
            rdb.rawQuery(
                """
                SELECT uc.*, u1.name as fromName, u2.name as toName
                  FROM ${DatabaseHelper.T_UNIT_CONVERSIONS} uc
                  JOIN ${DatabaseHelper.T_UOMS} u1 ON uc.fromUomId = u1.id
                  JOIN ${DatabaseHelper.T_UOMS} u2 ON uc.toUomId   = u2.id
                """.trimIndent(), null
            )
        ).toString()

    fun saveConversion(json: String): String {
        val data = JSONObject(json)
        val values = ContentValues().apply {
            put("fromUomId",        data.getInt("fromUomId"))
            put("toUomId",          data.getInt("toUomId"))
            put("conversionFactor", data.getDouble("conversionFactor"))
        }
        val id = data.optInt("id", 0)
        return try {
            if (id > 0) {
                val rows = db.update(DatabaseHelper.T_UNIT_CONVERSIONS, values, "id = ?", arrayOf(id.toString()))
                if (rows > 0) {
                    JSONObject().put("id", id).toString()
                } else {
                    val existingId = db.rawQuery(
                        "SELECT id FROM ${DatabaseHelper.T_UNIT_CONVERSIONS} WHERE fromUomId = ? AND toUomId = ?",
                        arrayOf(data.getInt("fromUomId").toString(), data.getInt("toUomId").toString())
                    ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
                    if (existingId > 0) {
                        db.update(DatabaseHelper.T_UNIT_CONVERSIONS, values, "id = ?", arrayOf(existingId.toString()))
                        JSONObject().put("id", existingId).toString()
                    } else {
                        JSONObject().put("id", db.insert(DatabaseHelper.T_UNIT_CONVERSIONS, null, values)).toString()
                    }
                }
            } else {
                JSONObject().put("id", db.insert(DatabaseHelper.T_UNIT_CONVERSIONS, null, values)).toString()
            }
        } catch (e: Exception) {
            JSONObject().put("status", "error").put("message", e.message).toString()
        }
    }

    fun deleteConversion(conversionId: Int): Boolean =
        db.delete(DatabaseHelper.T_UNIT_CONVERSIONS, "id = ?", arrayOf(conversionId.toString())) > 0

    fun getConversion(fromUnit: String, toUnit: String): String =
        helper.fetchAll(
            rdb.rawQuery(
                "SELECT ConversionFactor FROM ${DatabaseHelper.T_UNIT_CONVERSIONS} WHERE fromUomId = ? AND toUomId = ?",
                arrayOf(fromUnit, toUnit)
            )
        ).toString()

    fun getConversionFactor(fromUomId: Int, toUomId: Int): Double {
        rdb.rawQuery(
            "SELECT conversionFactor FROM ${DatabaseHelper.T_UNIT_CONVERSIONS} WHERE fromUomId = ? AND toUomId = ?",
            arrayOf(fromUomId.toString(), toUomId.toString())
        ).use { c -> if (c.moveToFirst()) return c.getDouble(0) }
        return 1.0
    }
}
