package com.example.dairypos.data.repository.reference

import android.content.ContentValues
import android.util.Base64
import android.util.Log
import androidx.core.database.getDoubleOrNull
import androidx.core.database.getIntOrNull
import com.example.dairypos.CustomerDto
import com.example.dairypos.DatabaseHelper
import com.example.dairypos.model.Customer
import com.example.dairypos.model.DailyFinancialsSummary
import org.json.JSONArray
import org.json.JSONObject

/** Reference data for customers, locations, and photos. No accounting. */
class r5_CustomerRepository(private val helper: DatabaseHelper) {
    private val db  get() = helper.writableDatabase
    private val rdb get() = helper.readableDatabase

    fun saveCustomer(json: String): String {
        val obj = JSONObject(json)
        val id = obj.optInt("id", 0)
        val cv = ContentValues().apply {
            put("name",       obj.getString("name"))
            put("address",    obj.optString("address", ""))
            put("phone",      obj.optString("phone", ""))
            put("rate",       obj.getInt("rate"))
            put("quantity",   obj.getDouble("quantity"))
            put("classId",    obj.getInt("classId"))
            put("updateDate", obj.optString("updateDate", obj.optString("createDate", "")))
            put("updatedOn",  System.currentTimeMillis())
        }
        val newId = if (id > 0) {
            db.update(DatabaseHelper.T_CUST, cv, "id=?", arrayOf(id.toString()))
            id
        } else {
            cv.put("createDate", obj.optString("createDate", helper.nowIso()))
            db.insert(DatabaseHelper.T_CUST, null, cv).toInt()
        }
        return JSONObject().put("id", newId).toString()
    }

    fun getAllCustomers(): String =
        helper.fetchAll(
            rdb.rawQuery(
                "SELECT CU.id, CU.name, CU.phone, CU.quantity, CU.rate, CU.classId, CU.createDate, CU.updateDate, CL.name as className\n" +
                "FROM Customers CU LEFT JOIN Classes CL ON CU.classId = CL.id WHERE CU.isDeleted=0", null
            )
        ).toString()

    fun getAllCustomersList(): List<CustomerDto> {
        val result = mutableListOf<CustomerDto>()
        val sql = """
            SELECT CU.id, CU.name, CU.phone, CU.quantity, CU.rate,
                   CU.classId, CU.createDate, CU.updateDate, CL.name AS className
              FROM Customers CU
              LEFT JOIN Classes CL ON CU.classId = CL.id
             WHERE CU.isDeleted=0
        """.trimIndent()
        try {
            rdb.rawQuery(sql, null).use { c ->
                val idCol         = c.getColumnIndexOrThrow("id")
                val nameCol       = c.getColumnIndexOrThrow("name")
                val phoneCol      = c.getColumnIndexOrThrow("phone")
                val qtyCol        = c.getColumnIndexOrThrow("quantity")
                val rateCol       = c.getColumnIndexOrThrow("rate")
                val classIdCol    = c.getColumnIndexOrThrow("classId")
                val createDateCol = c.getColumnIndexOrThrow("createDate")
                val updateDateCol = c.getColumnIndexOrThrow("updateDate")
                val classNameCol  = c.getColumnIndexOrThrow("className")
                while (c.moveToNext()) {
                    result.add(CustomerDto(
                        id         = c.getInt(idCol),
                        name       = c.getString(nameCol) ?: "",
                        phone      = c.getString(phoneCol),
                        quantity   = c.getDoubleOrNull(qtyCol),
                        rate       = c.getDoubleOrNull(rateCol),
                        classId    = c.getIntOrNull(classIdCol),
                        createDate = c.getString(createDateCol),
                        updateDate = c.getString(updateDateCol),
                        className  = c.getString(classNameCol)
                    ))
                }
            }
        } catch (ex: Exception) {
            Log.e("r5Customer", "getAllCustomersList failed", ex)
        }
        return result
    }

    fun searchCustomers(filter: String, classFilterId: Int?): String {
        val whereClauses = mutableListOf<String>()
        val argsList = mutableListOf<String>()
        if (filter.isNotBlank()) {
            val wildcard = "%${filter}%"
            whereClauses += "(CU.id LIKE ? OR CU.name LIKE ? OR CU.phone LIKE ? OR CU.quantity LIKE ? OR CU.rate LIKE ?)"
            repeat(5) { argsList += wildcard }
        }
        classFilterId?.let { whereClauses += "CU.classId = ?"; argsList += it.toString() }
        val whereSection = if (whereClauses.isNotEmpty()) "WHERE " + whereClauses.joinToString(" AND ") else ""
        val sql = """
            SELECT CU.id, CU.name, CU.phone, CU.quantity, CU.rate,
                   CU.classId, CU.createDate, CU.updateDate, CL.name AS className
              FROM Customers CU
              LEFT JOIN Classes CL ON CU.classId = CL.id
             WHERE CU.isDeleted=0
               ${if (whereSection.isNotEmpty()) "AND " + whereSection.removePrefix("WHERE ") else ""}
             ORDER BY CU.id ASC
        """.trimIndent()
        return helper.fetchAll(rdb.rawQuery(sql, argsList.toTypedArray())).toString()
    }

    fun getCustomerLocations(customerId: Int): String {
        return try {
            val sql = "SELECT latitude, longitude, capturedAt FROM ${DatabaseHelper.T_CUST_LOCATIONS} WHERE customerId = ? AND isDeleted=0 ORDER BY id DESC LIMIT 2"
            helper.fetchAll(rdb.rawQuery(sql, arrayOf(customerId.toString()))).toString()
        } catch (ex: Exception) {
            Log.e("r5Customer", "getCustomerLocations failed", ex)
            "[]"
        }
    }

    fun deleteCustomerLocation(id: Int) {
        db.execSQL(
            "UPDATE ${DatabaseHelper.T_CUST_LOCATIONS} SET isDeleted=1, updatedOn=${System.currentTimeMillis()} WHERE id=?",
            arrayOf(id)
        )
    }

    fun deleteCustomerPhoto(id: Int) {
        db.execSQL("DELETE FROM ${DatabaseHelper.T_CUST_PHOTOS} WHERE id = ?", arrayOf(id))
    }

    fun updateMapUrl(id: Int, url: String) {
        db.compileStatement(
            "UPDATE ${DatabaseHelper.T_CUST_LOCATIONS} SET mapUrl = ?, updatedOn = ? WHERE id = ?"
        ).apply { bindString(1, url); bindLong(2, System.currentTimeMillis()); bindLong(3, id.toLong()); executeUpdateDelete() }
    }

    fun updateCustomerLatLon(id: Int, lat: Double, lon: Double) {
        db.compileStatement(
            "UPDATE ${DatabaseHelper.T_CUST_LOCATIONS} SET latitude = ?, longitude = ?, capturedAt = datetime('now'), updatedOn = ? WHERE id = ?"
        ).apply { bindDouble(1, lat); bindDouble(2, lon); bindLong(3, System.currentTimeMillis()); bindLong(4, id.toLong()); executeUpdateDelete() }
    }

    fun getCustomerPhotos(customerId: Int): String {
        val arr = org.json.JSONArray()
        rdb.rawQuery(
            "SELECT imageBlob, createdAt FROM ${DatabaseHelper.T_CUST_PHOTOS} WHERE customerId=? ORDER BY id DESC LIMIT 4",
            arrayOf(customerId.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val base64 = Base64.encodeToString(c.getBlob(0), Base64.DEFAULT)
                arr.put(JSONObject().put("base64", base64).put("capturedAt", c.getString(1)))
            }
        }
        return arr.toString()
    }

    fun insertCustomerLocation(customerId: Int, lat: Double, lon: Double, accuracy: Double = 0.00) {
        db.compileStatement(
            "INSERT INTO ${DatabaseHelper.T_CUST_LOCATIONS} (customerId, latitude, longitude, geoAccuracy, capturedAt, updatedOn) VALUES (?, ?, ?, ?, datetime('now'), ?)"
        ).apply {
            bindLong(1, customerId.toLong()); bindDouble(2, lat); bindDouble(3, lon); bindDouble(4, accuracy); bindLong(5, System.currentTimeMillis())
            executeInsert()
        }
    }

    fun insertCustomerPhoto(customerId: Int, bytes: ByteArray, caption: String) {
        db.compileStatement(
            "INSERT INTO ${DatabaseHelper.T_CUST_PHOTOS} (customerId, imageBlob, caption, createdAt) VALUES (?, ?, ?, datetime('now'))"
        ).apply {
            bindLong(1, customerId.toLong()); bindBlob(2, bytes); bindString(3, caption)
            executeInsert()
        }
    }
}
