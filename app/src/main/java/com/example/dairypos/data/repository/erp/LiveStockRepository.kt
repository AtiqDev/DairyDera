package com.example.dairypos.data.repository.erp

import android.content.ContentValues
import com.example.dairypos.DatabaseHelper
import org.json.JSONObject

class LiveStockRepository(private val helper: DatabaseHelper) {
    private val db  get() = helper.writableDatabase
    private val rdb get() = helper.readableDatabase

    // ── Groups ─────────────────────────────────────────────────────────────

    fun getGroups(): String {
        val sql = "SELECT id, name, notes FROM ${DatabaseHelper.T_ANIMAL_GROUPS} ORDER BY name"
        return helper.fetchAll(rdb.rawQuery(sql, null)).toString()
    }

    fun saveGroup(json: String): String {
        return try {
            val obj = JSONObject(json)
            val cv = ContentValues().apply {
                put("name",  obj.getString("name"))
                put("notes", obj.optString("notes", ""))
            }
            val id = if (obj.has("id") && obj.getInt("id") > 0) {
                db.update(DatabaseHelper.T_ANIMAL_GROUPS, cv, "id=?",
                    arrayOf(obj.getInt("id").toString()))
                obj.getInt("id").toLong()
            } else {
                db.insert(DatabaseHelper.T_ANIMAL_GROUPS, null, cv)
            }
            JSONObject().put("id", id).toString()
        } catch (ex: Exception) {
            JSONObject().put("error", ex.message).toString()
        }
    }

    fun deleteGroup(id: Int): String {
        return try {
            db.delete(DatabaseHelper.T_ANIMAL_GROUPS, "id=?", arrayOf(id.toString()))
            JSONObject().put("status", "deleted").toString()
        } catch (ex: Exception) {
            JSONObject().put("error", ex.message).toString()
        }
    }

    // ── Animals ────────────────────────────────────────────────────────────

    fun getAnimals(groupId: Int? = null): String {
        val where = if (groupId != null) "WHERE a.groupId = $groupId" else ""
        val sql = """
            SELECT a.id, a.tagNumber, a.name, a.breed, a.gender, a.status,
                   a.dateOfBirth, a.bookValue,
                   g.name AS groupName,
                   dam.tagNumber AS damTag
              FROM ${DatabaseHelper.T_ANIMALS} a
              LEFT JOIN ${DatabaseHelper.T_ANIMAL_GROUPS} g ON g.id = a.groupId
              LEFT JOIN ${DatabaseHelper.T_ANIMALS} dam ON dam.id = a.damId
              $where
             ORDER BY a.tagNumber
        """.trimIndent()
        return helper.fetchAll(rdb.rawQuery(sql, null)).toString()
    }

    fun getAnimalById(id: Int): String {
        val sql = """
            SELECT a.*, g.name AS groupName, dam.tagNumber AS damTag
              FROM ${DatabaseHelper.T_ANIMALS} a
              LEFT JOIN ${DatabaseHelper.T_ANIMAL_GROUPS} g ON g.id = a.groupId
              LEFT JOIN ${DatabaseHelper.T_ANIMALS} dam ON dam.id = a.damId
             WHERE a.id = ?
        """.trimIndent()
        val arr = helper.fetchAll(rdb.rawQuery(sql, arrayOf(id.toString())))
        return if (arr.length() > 0) arr.getJSONObject(0).toString()
        else JSONObject().put("error", "not found").toString()
    }

    fun saveAnimal(json: String): String {
        return try {
            val obj = JSONObject(json)
            val cv = ContentValues().apply {
                put("tagNumber",   obj.getString("tagNumber"))
                put("name",        obj.optString("name", ""))
                put("breed",       obj.optString("breed", ""))
                put("gender",      obj.getString("gender"))
                put("dateOfBirth", obj.optString("dateOfBirth", ""))
                put("sireInfo",    obj.optString("sireInfo", ""))
                put("notes",       obj.optString("notes", ""))
                if (obj.has("groupId") && !obj.isNull("groupId"))
                    put("groupId", obj.getInt("groupId"))
                if (obj.has("damId") && !obj.isNull("damId"))
                    put("damId", obj.getInt("damId"))
                if (obj.has("bookValue") && !obj.isNull("bookValue"))
                    put("bookValue", obj.getDouble("bookValue"))
            }
            val id = if (obj.has("id") && obj.getInt("id") > 0) {
                db.update(DatabaseHelper.T_ANIMALS, cv, "id=?",
                    arrayOf(obj.getInt("id").toString()))
                obj.getInt("id").toLong()
            } else {
                cv.put("status", "active")
                db.insert(DatabaseHelper.T_ANIMALS, null, cv)
            }
            JSONObject().put("id", id).toString()
        } catch (ex: Exception) {
            JSONObject().put("error", ex.message).toString()
        }
    }

    fun updateAnimalStatus(id: Int, status: String): String {
        return try {
            val cv = ContentValues().apply { put("status", status) }
            db.update(DatabaseHelper.T_ANIMALS, cv, "id=?", arrayOf(id.toString()))
            JSONObject().put("status", "ok").toString()
        } catch (ex: Exception) {
            JSONObject().put("error", ex.message).toString()
        }
    }

    fun searchAnimals(query: String): String {
        val q = "%$query%"
        val sql = """
            SELECT a.id, a.tagNumber, a.name, a.breed, a.gender, a.status,
                   g.name AS groupName
              FROM ${DatabaseHelper.T_ANIMALS} a
              LEFT JOIN ${DatabaseHelper.T_ANIMAL_GROUPS} g ON g.id = a.groupId
             WHERE a.tagNumber LIKE ? OR a.name LIKE ?
             ORDER BY a.tagNumber
        """.trimIndent()
        return helper.fetchAll(rdb.rawQuery(sql, arrayOf(q, q))).toString()
    }

    fun getHerdSummary(): String {
        val sql = """
            SELECT
                COUNT(*) AS total,
                SUM(CASE WHEN status='active'    THEN 1 ELSE 0 END) AS active,
                SUM(CASE WHEN status='dry'       THEN 1 ELSE 0 END) AS dry,
                SUM(CASE WHEN status='pregnant'  THEN 1 ELSE 0 END) AS pregnant,
                SUM(CASE WHEN status='sick'      THEN 1 ELSE 0 END) AS sick,
                SUM(CASE WHEN gender='F'         THEN 1 ELSE 0 END) AS females,
                SUM(CASE WHEN gender='M'         THEN 1 ELSE 0 END) AS males,
                SUM(CASE WHEN gender='C'         THEN 1 ELSE 0 END) AS calves,
                ROUND(COALESCE(SUM(bookValue),0),2) AS totalBookValue
              FROM ${DatabaseHelper.T_ANIMALS}
             WHERE status NOT IN ('sold','dead')
        """.trimIndent()
        val arr = helper.fetchAll(rdb.rawQuery(sql, null))
        return if (arr.length() > 0) arr.getJSONObject(0).toString()
        else JSONObject().toString()
    }
}
