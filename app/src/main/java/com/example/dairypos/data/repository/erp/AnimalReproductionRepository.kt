package com.example.dairypos.data.repository.erp

import android.content.ContentValues
import android.util.Log
import com.example.dairypos.DatabaseHelper
import org.json.JSONObject

class AnimalReproductionRepository(private val helper: DatabaseHelper) {
    private val db  get() = helper.writableDatabase
    private val rdb get() = helper.readableDatabase

    // ── Cycle management ─────────────────────────────────────────────────────

    fun getReproductionHistory(animalId: Int): String {
        val sql = """
            SELECT r.*,
                   calf.tagNumber AS calfTag
              FROM ${DatabaseHelper.T_ANIMAL_REPRODUCTION} r
              LEFT JOIN ${DatabaseHelper.T_ANIMALS} calf ON calf.id = r.calfId
             WHERE r.animalId = ?
             ORDER BY r.createdAt DESC
        """.trimIndent()
        return helper.fetchAll(rdb.rawQuery(sql, arrayOf(animalId.toString()))).toString()
    }

    fun recordHeat(json: String): String {
        return try {
            val obj      = JSONObject(json)
            val animalId = obj.getInt("animalId")

            val cycleNumber = if (obj.has("cycleNumber") && !obj.isNull("cycleNumber"))
                obj.getInt("cycleNumber")
            else helper.fetchScalarInt(
                "SELECT COALESCE(MAX(cycleNumber), 0) + 1 FROM ${DatabaseHelper.T_ANIMAL_REPRODUCTION} WHERE animalId=?",
                arrayOf(animalId.toString())
            )

            val cv = ContentValues().apply {
                put("animalId",    animalId)
                put("heatDate",    obj.getString("heatDate"))
                put("cycleNumber", cycleNumber)
                put("outcome",     "pending")
                put("notes",       obj.optString("notes", ""))
            }
            val id = db.insert(DatabaseHelper.T_ANIMAL_REPRODUCTION, null, cv)
            JSONObject().put("id", id).put("cycleNumber", cycleNumber).toString()
        } catch (ex: Exception) {
            Log.e("ReproRepo", "recordHeat failed", ex)
            JSONObject().put("error", ex.message).toString()
        }
    }

    fun recordInsemination(json: String): String {
        return try {
            val obj = JSONObject(json)
            val cv = ContentValues().apply {
                put("inseminationDate", obj.getString("inseminationDate"))
                put("inseminationType", obj.getString("inseminationType"))
                put("bullInfo",         obj.optString("bullInfo", ""))
                if (obj.has("notes") && !obj.isNull("notes"))
                    put("notes", obj.getString("notes"))
            }
            db.update(DatabaseHelper.T_ANIMAL_REPRODUCTION, cv,
                "id=?", arrayOf(obj.getInt("cycleId").toString()))
            JSONObject().put("status", "ok").toString()
        } catch (ex: Exception) {
            Log.e("ReproRepo", "recordInsemination failed", ex)
            JSONObject().put("error", ex.message).toString()
        }
    }

    fun updatePregnancyCheck(json: String): String {
        return try {
            val obj       = JSONObject(json)
            val confirmed = obj.getInt("pregnancyConfirmed")
            val cv = ContentValues().apply {
                put("pregnancyCheckDate", obj.getString("pregnancyCheckDate"))
                put("pregnancyConfirmed", confirmed)
                if (confirmed == 1 && obj.has("expectedCalvingDate") && !obj.isNull("expectedCalvingDate"))
                    put("expectedCalvingDate", obj.getString("expectedCalvingDate"))
            }
            db.update(DatabaseHelper.T_ANIMAL_REPRODUCTION, cv,
                "id=?", arrayOf(obj.getInt("cycleId").toString()))

            if (confirmed == 1) {
                val animalId = helper.fetchScalarInt(
                    "SELECT animalId FROM ${DatabaseHelper.T_ANIMAL_REPRODUCTION} WHERE id=?",
                    arrayOf(obj.getInt("cycleId").toString())
                )
                if (animalId > 0) {
                    db.update(DatabaseHelper.T_ANIMALS,
                        ContentValues().apply { put("status", "pregnant") },
                        "id=?", arrayOf(animalId.toString()))
                }
            }
            JSONObject().put("status", "ok").toString()
        } catch (ex: Exception) {
            Log.e("ReproRepo", "updatePregnancyCheck failed", ex)
            JSONObject().put("error", ex.message).toString()
        }
    }

    fun recordCalving(json: String): String {
        return try {
            val obj     = JSONObject(json)
            val cycleId = obj.getInt("cycleId")
            val date    = obj.getString("actualCalvingDate")
            val outcome = obj.getString("outcome")

            val animalId = helper.fetchScalarInt(
                "SELECT animalId FROM ${DatabaseHelper.T_ANIMAL_REPRODUCTION} WHERE id=?",
                arrayOf(cycleId.toString())
            )
            if (animalId <= 0) return JSONObject().put("error", "Cycle not found").toString()

            var calfId: Long? = null
            if (outcome == "live") {
                val calfCv = ContentValues().apply {
                    put("tagNumber",   obj.getString("calfTag"))
                    put("gender",      "C")
                    put("dateOfBirth", date)
                    put("damId",       animalId)
                    put("status",      "active")
                    put("bookValue",   0.0)
                    put("notes",       obj.optString("notes", ""))
                }
                val damGroupId = helper.fetchScalarInt(
                    "SELECT COALESCE(groupId, 0) FROM ${DatabaseHelper.T_ANIMALS} WHERE id=?",
                    arrayOf(animalId.toString())
                )
                if (damGroupId > 0) calfCv.put("groupId", damGroupId)
                calfId = db.insert(DatabaseHelper.T_ANIMALS, null, calfCv)
            }

            val cycleCv = ContentValues().apply {
                put("actualCalvingDate", date)
                put("calfGender",        obj.optString("calfGender", ""))
                put("outcome",           outcome)
                if (calfId != null) put("calfId", calfId)
            }
            db.update(DatabaseHelper.T_ANIMAL_REPRODUCTION, cycleCv, "id=?",
                arrayOf(cycleId.toString()))

            db.update(DatabaseHelper.T_ANIMALS,
                ContentValues().apply { put("status", "active") },
                "id=?", arrayOf(animalId.toString()))

            val lactationNumber = helper.fetchScalarInt(
                "SELECT COALESCE(MAX(lactationNumber), 0) + 1 FROM ${DatabaseHelper.T_ANIMAL_LACTATION} WHERE animalId=?",
                arrayOf(animalId.toString())
            )
            db.insert(DatabaseHelper.T_ANIMAL_LACTATION, null, ContentValues().apply {
                put("animalId",        animalId)
                put("lactationNumber", lactationNumber)
                put("startDate",       date)
                put("status",          "active")
            })

            JSONObject().put("calfId", calfId ?: 0).put("lactationNumber", lactationNumber).toString()
        } catch (ex: Exception) {
            Log.e("ReproRepo", "recordCalving failed", ex)
            JSONObject().put("error", ex.message).toString()
        }
    }

    // ── Dashboard queries ────────────────────────────────────────────────────

    fun getExpectedCalvings(): String {
        val sql = """
            SELECT r.id AS cycleId, r.animalId, a.tagNumber, a.name AS animalName,
                   r.expectedCalvingDate, r.inseminationDate,
                   CAST(julianday(r.expectedCalvingDate) - julianday('now') AS INTEGER) AS daysUntilCalving
              FROM ${DatabaseHelper.T_ANIMAL_REPRODUCTION} r
              JOIN ${DatabaseHelper.T_ANIMALS} a ON a.id = r.animalId
             WHERE r.outcome = 'pending'
               AND r.pregnancyConfirmed = 1
               AND r.expectedCalvingDate IS NOT NULL
             ORDER BY r.expectedCalvingDate ASC
        """.trimIndent()
        return helper.fetchAll(rdb.rawQuery(sql, null)).toString()
    }

    fun getActiveCycles(): String {
        val sql = """
            SELECT r.id, r.animalId, r.cycleNumber,
                   a.tagNumber, a.name AS animalName,
                   r.heatDate, r.inseminationDate, r.inseminationType,
                   r.pregnancyCheckDate, r.pregnancyConfirmed,
                   r.expectedCalvingDate, r.outcome
              FROM ${DatabaseHelper.T_ANIMAL_REPRODUCTION} r
              JOIN ${DatabaseHelper.T_ANIMALS} a ON a.id = r.animalId
             WHERE r.outcome = 'pending'
             ORDER BY r.heatDate DESC
        """.trimIndent()
        return helper.fetchAll(rdb.rawQuery(sql, null)).toString()
    }
}
