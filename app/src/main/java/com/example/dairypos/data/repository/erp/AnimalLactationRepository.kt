package com.example.dairypos.data.repository.erp

import android.content.ContentValues
import android.util.Log
import com.example.dairypos.DatabaseHelper
import org.json.JSONObject

class AnimalLactationRepository(private val helper: DatabaseHelper) {
    private val db  get() = helper.writableDatabase
    private val rdb get() = helper.readableDatabase

    // ── Queries ──────────────────────────────────────────────────────────────

    fun getLactationHistory(animalId: Int): String {
        val sql = """
            SELECT l.id, l.lactationNumber, l.startDate, l.dryOffDate, l.status, l.notes,
                   CASE
                     WHEN l.dryOffDate IS NOT NULL THEN
                       CAST(julianday(l.dryOffDate) - julianday(l.startDate) AS INTEGER)
                     ELSE
                       CAST(julianday('now') - julianday(l.startDate) AS INTEGER)
                   END AS daysInLactation
              FROM ${DatabaseHelper.T_ANIMAL_LACTATION} l
             WHERE l.animalId = ?
             ORDER BY l.lactationNumber DESC
        """.trimIndent()
        return helper.fetchAll(rdb.rawQuery(sql, arrayOf(animalId.toString()))).toString()
    }

    fun getActiveLactations(): String {
        val sql = """
            SELECT l.id, l.animalId, l.lactationNumber, l.startDate,
                   CAST(julianday('now') - julianday(l.startDate) AS INTEGER) AS daysInLactation,
                   a.tagNumber, a.name AS animalName, a.breed,
                   g.name AS groupName
              FROM ${DatabaseHelper.T_ANIMAL_LACTATION} l
              JOIN ${DatabaseHelper.T_ANIMALS} a ON a.id = l.animalId
              LEFT JOIN ${DatabaseHelper.T_ANIMAL_GROUPS} g ON g.id = a.groupId
             WHERE l.status = 'active'
               AND a.status NOT IN ('sold','dead')
             ORDER BY l.startDate ASC
        """.trimIndent()
        return helper.fetchAll(rdb.rawQuery(sql, null)).toString()
    }

    fun getDryCows(): String {
        val sql = """
            SELECT l.id, l.animalId, l.lactationNumber, l.startDate, l.dryOffDate,
                   CAST(julianday('now') - julianday(l.dryOffDate) AS INTEGER) AS daysDry,
                   a.tagNumber, a.name AS animalName,
                   g.name AS groupName
              FROM ${DatabaseHelper.T_ANIMAL_LACTATION} l
              JOIN ${DatabaseHelper.T_ANIMALS} a ON a.id = l.animalId
              LEFT JOIN ${DatabaseHelper.T_ANIMAL_GROUPS} g ON g.id = a.groupId
             WHERE l.status = 'dry'
               AND a.status NOT IN ('sold','dead')
             ORDER BY l.dryOffDate DESC
        """.trimIndent()
        return helper.fetchAll(rdb.rawQuery(sql, null)).toString()
    }

    fun getLactationSummary(): String {
        val sql = """
            SELECT
                SUM(CASE WHEN l.status='active' THEN 1 ELSE 0 END) AS activeLactating,
                SUM(CASE WHEN l.status='dry'    THEN 1 ELSE 0 END) AS currentlyDry,
                ROUND(AVG(CASE WHEN l.status='active'
                    THEN CAST(julianday('now') - julianday(l.startDate) AS INTEGER) END), 0) AS avgDIM
              FROM ${DatabaseHelper.T_ANIMAL_LACTATION} l
              JOIN ${DatabaseHelper.T_ANIMALS} a ON a.id = l.animalId
             WHERE a.status NOT IN ('sold','dead')
               AND l.status IN ('active','dry')
        """.trimIndent()
        val arr = helper.fetchAll(rdb.rawQuery(sql, null))
        return if (arr.length() > 0) arr.getJSONObject(0).toString()
        else JSONObject().toString()
    }

    // ── Mutations ────────────────────────────────────────────────────────────

    fun createLactation(json: String): String {
        return try {
            val obj      = JSONObject(json)
            val animalId = obj.getInt("animalId")

            val lactationNumber = if (obj.has("lactationNumber") && !obj.isNull("lactationNumber"))
                obj.getInt("lactationNumber")
            else helper.fetchScalarInt(
                "SELECT COALESCE(MAX(lactationNumber), 0) + 1 FROM ${DatabaseHelper.T_ANIMAL_LACTATION} WHERE animalId=?",
                arrayOf(animalId.toString())
            )

            val cv = ContentValues().apply {
                put("animalId",        animalId)
                put("lactationNumber", lactationNumber)
                put("startDate",       obj.getString("startDate"))
                put("status",          "active")
                put("notes",           obj.optString("notes", ""))
            }
            val id = db.insert(DatabaseHelper.T_ANIMAL_LACTATION, null, cv)

            db.update(DatabaseHelper.T_ANIMALS,
                ContentValues().apply { put("status", "active") },
                "id=?", arrayOf(animalId.toString()))

            JSONObject().put("id", id).put("lactationNumber", lactationNumber).toString()
        } catch (ex: Exception) {
            Log.e("LactationRepo", "createLactation failed", ex)
            JSONObject().put("error", ex.message).toString()
        }
    }

    fun recordDryOff(json: String): String {
        return try {
            val obj         = JSONObject(json)
            val lactationId = obj.getInt("lactationId")
            val dryOffDate  = obj.getString("dryOffDate")

            val cv = ContentValues().apply {
                put("dryOffDate", dryOffDate)
                put("status",     "dry")
                if (obj.has("notes") && !obj.isNull("notes"))
                    put("notes", obj.getString("notes"))
            }
            db.update(DatabaseHelper.T_ANIMAL_LACTATION, cv, "id=?",
                arrayOf(lactationId.toString()))

            val animalId = helper.fetchScalarInt(
                "SELECT animalId FROM ${DatabaseHelper.T_ANIMAL_LACTATION} WHERE id=?",
                arrayOf(lactationId.toString())
            )
            if (animalId > 0) {
                db.update(DatabaseHelper.T_ANIMALS,
                    ContentValues().apply { put("status", "dry") },
                    "id=?", arrayOf(animalId.toString()))
            }

            JSONObject().put("status", "ok").toString()
        } catch (ex: Exception) {
            Log.e("LactationRepo", "recordDryOff failed", ex)
            JSONObject().put("error", ex.message).toString()
        }
    }

    fun undoDryOff(lactationId: Int): String {
        return try {
            val cv = ContentValues().apply {
                put("status", "active")
                putNull("dryOffDate")
            }
            db.update(DatabaseHelper.T_ANIMAL_LACTATION, cv, "id=?",
                arrayOf(lactationId.toString()))

            val animalId = helper.fetchScalarInt(
                "SELECT animalId FROM ${DatabaseHelper.T_ANIMAL_LACTATION} WHERE id=?",
                arrayOf(lactationId.toString())
            )
            if (animalId > 0) {
                db.update(DatabaseHelper.T_ANIMALS,
                    ContentValues().apply { put("status", "active") },
                    "id=?", arrayOf(animalId.toString()))
            }
            JSONObject().put("status", "ok").toString()
        } catch (ex: Exception) {
            JSONObject().put("error", ex.message).toString()
        }
    }
}
