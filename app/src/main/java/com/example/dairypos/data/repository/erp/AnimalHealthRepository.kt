package com.example.dairypos.data.repository.erp

import android.content.ContentValues
import android.util.Log
import com.example.dairypos.DatabaseHelper
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class AnimalHealthRepository(private val helper: DatabaseHelper) {
    private val db  get() = helper.writableDatabase
    private val rdb get() = helper.readableDatabase

    // ── Vaccination Schedules ────────────────────────────────────────────────

    fun getSchedules(): String {
        val sql = """
            SELECT id, name, intervalDays, notes
              FROM ${DatabaseHelper.T_VACCINATION_SCHEDULES}
             ORDER BY name
        """.trimIndent()
        return helper.fetchAll(rdb.rawQuery(sql, null)).toString()
    }

    fun saveSchedule(json: String): String {
        return try {
            val obj = JSONObject(json)
            val cv = ContentValues().apply {
                put("name", obj.getString("name"))
                put("intervalDays", if (obj.has("intervalDays") && !obj.isNull("intervalDays"))
                                        obj.getInt("intervalDays") else null)
                put("notes", obj.optString("notes", ""))
            }
            val id = if (obj.has("id") && obj.getInt("id") > 0) {
                db.update(DatabaseHelper.T_VACCINATION_SCHEDULES, cv, "id=?",
                    arrayOf(obj.getInt("id").toString()))
                obj.getInt("id").toLong()
            } else {
                db.insert(DatabaseHelper.T_VACCINATION_SCHEDULES, null, cv)
            }
            JSONObject().put("id", id).toString()
        } catch (ex: Exception) {
            JSONObject().put("error", ex.message).toString()
        }
    }

    fun deleteSchedule(id: Int): String {
        return try {
            db.delete(DatabaseHelper.T_VACCINATION_SCHEDULES, "id=?", arrayOf(id.toString()))
            JSONObject().put("status", "deleted").toString()
        } catch (ex: Exception) {
            JSONObject().put("error", ex.message).toString()
        }
    }

    // ── Health Events ────────────────────────────────────────────────────────

    fun getHealthEvents(animalId: Int): String {
        val sql = """
            SELECT he.id, he.eventType, he.date, he.description,
                   he.medication, he.dosage, he.vetName, he.cost,
                   he.nextDueDate, he.notes,
                   vs.name AS scheduleName
              FROM ${DatabaseHelper.T_ANIMAL_HEALTH_EVENTS} he
              LEFT JOIN ${DatabaseHelper.T_VACCINATION_SCHEDULES} vs ON vs.id = he.scheduleId
             WHERE he.animalId = ?
             ORDER BY he.date DESC
        """.trimIndent()
        return helper.fetchAll(rdb.rawQuery(sql, arrayOf(animalId.toString()))).toString()
    }

    fun saveHealthEvent(json: String): String {
        return try {
            val obj = JSONObject(json)
            val cv = ContentValues().apply {
                put("animalId",    obj.getInt("animalId"))
                put("eventType",   obj.getString("eventType"))
                put("date",        obj.getString("date"))
                put("description", obj.getString("description"))
                put("medication",  obj.optString("medication", ""))
                put("dosage",      obj.optString("dosage", ""))
                put("vetName",     obj.optString("vetName", ""))
                put("notes",       obj.optString("notes", ""))
                if (obj.has("cost") && !obj.isNull("cost"))
                    put("cost", obj.getDouble("cost"))
                if (obj.has("scheduleId") && !obj.isNull("scheduleId"))
                    put("scheduleId", obj.getInt("scheduleId"))

                if (obj.has("scheduleId") && !obj.isNull("scheduleId")) {
                    val scheduleId = obj.getInt("scheduleId")
                    val intervalDays = helper.fetchScalarInt(
                        "SELECT COALESCE(intervalDays, 0) FROM ${DatabaseHelper.T_VACCINATION_SCHEDULES} WHERE id=?",
                        arrayOf(scheduleId.toString())
                    )
                    if (intervalDays > 0) {
                        val eventDate = LocalDate.parse(obj.getString("date"),
                            DateTimeFormatter.ISO_LOCAL_DATE)
                        val nextDue = eventDate.plusDays(intervalDays.toLong())
                            .format(DateTimeFormatter.ISO_LOCAL_DATE)
                        put("nextDueDate", nextDue)
                    }
                }
            }
            val id = db.insert(DatabaseHelper.T_ANIMAL_HEALTH_EVENTS, null, cv)
            JSONObject().put("id", id).toString()
        } catch (ex: Exception) {
            Log.e("AnimalHealthRepo", "saveHealthEvent failed", ex)
            JSONObject().put("error", ex.message).toString()
        }
    }

    fun deleteHealthEvent(id: Int): String {
        return try {
            db.delete(DatabaseHelper.T_ANIMAL_HEALTH_EVENTS, "id=?", arrayOf(id.toString()))
            JSONObject().put("status", "deleted").toString()
        } catch (ex: Exception) {
            JSONObject().put("error", ex.message).toString()
        }
    }

    // ── Overdue Alerts ───────────────────────────────────────────────────────

    fun getOverdueVaccinations(): String {
        val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val sql = """
            SELECT he.animalId, a.tagNumber, a.name AS animalName,
                   vs.name AS vaccineName, he.nextDueDate,
                   CAST(julianday(?) - julianday(he.nextDueDate) AS INTEGER) AS daysOverdue
              FROM ${DatabaseHelper.T_ANIMAL_HEALTH_EVENTS} he
              JOIN ${DatabaseHelper.T_ANIMALS} a ON a.id = he.animalId
              JOIN ${DatabaseHelper.T_VACCINATION_SCHEDULES} vs ON vs.id = he.scheduleId
             WHERE he.nextDueDate IS NOT NULL
               AND he.nextDueDate <= ?
               AND a.status NOT IN ('sold','dead')
               AND NOT EXISTS (
                   SELECT 1
                     FROM ${DatabaseHelper.T_ANIMAL_HEALTH_EVENTS} he2
                    WHERE he2.animalId  = he.animalId
                      AND he2.scheduleId = he.scheduleId
                      AND he2.date > he.date
               )
             ORDER BY he.nextDueDate ASC
        """.trimIndent()
        return helper.fetchAll(rdb.rawQuery(sql, arrayOf(today, today))).toString()
    }

    // ── Summary for animal profile ───────────────────────────────────────────

    fun getHealthSummary(animalId: Int): String {
        val sql = """
            SELECT
                COUNT(*) AS totalEvents,
                SUM(CASE WHEN eventType='vaccination' THEN 1 ELSE 0 END) AS vaccinations,
                SUM(CASE WHEN eventType='treatment'   THEN 1 ELSE 0 END) AS treatments,
                ROUND(COALESCE(SUM(cost), 0), 2) AS totalVetCost,
                MAX(date) AS lastEventDate
              FROM ${DatabaseHelper.T_ANIMAL_HEALTH_EVENTS}
             WHERE animalId = ?
        """.trimIndent()
        val arr = helper.fetchAll(rdb.rawQuery(sql, arrayOf(animalId.toString())))
        return if (arr.length() > 0) arr.getJSONObject(0).toString()
        else JSONObject().toString()
    }
}
